---
title: Spring @Transactional Self-Invocation Bypasses AOP Proxy in Scheduled Outbox Dispatcher
date: 2026-08-04
category: logic-errors
module: outbox-dispatcher
problem_type: logic_error
component: background_job
symptoms:
  - "outbox_event rows never leave PENDING status despite the 3-second scheduled poll running continuously"
  - "retry_count never increments even though dispatchOne is invoked on every poll cycle"
  - "stub downstream client is invoked repeatedly on every poll cycle forever (unbounded duplicate delivery), only discovered by manually polling the outbox_event table via psql during a live bootRun"
root_cause: wrong_api
resolution_type: code_fix
severity: critical
related_components:
  - OutboxEventRepository
  - ReservationRepository
  - RoomInventoryRepository
  - testing_framework
tags:
  - spring-transactional
  - self-invocation
  - aop-proxy
  - outbox-pattern
  - detached-entity
  - open-in-view
---

# Spring @Transactional Self-Invocation Bypasses AOP Proxy in Scheduled Outbox Dispatcher

## Problem

`OutboxDispatcher` is a `@Scheduled` poller responsible for retrying `outbox_event` rows that are still `PENDING`. Its per-event work — looking up the event and the related reservation, calling the downstream client, and marking the event as succeeded or failed — needs to run inside a database transaction so that state changes (`retry_count`, `status`, `next_retry_at`) are actually persisted.

Before the fix, `dispatchPendingEvents()` (the scheduled entry point, itself not `@Transactional`) looped over pending events and called `dispatchOne(event.getId())` on itself — a same-class, same-bean method invocation. `dispatchOne` carried `@Transactional`, but that annotation was never honored on this call path, because Spring only applies method-level AOP advice when a call arrives through the bean's Spring-managed proxy. A call made from inside the bean's own code (`this.dispatchOne(...)` or the bare unqualified form) goes straight to the target object, bypassing the proxy — and therefore bypassing the transaction — entirely.

## Symptoms

- `outbox_event` rows never left `PENDING` in the running application.
- `retry_count` never incremented, no matter how long the app ran.
- The stub downstream client was invoked repeatedly, once per poll cycle (every 3 seconds), forever — unbounded duplicate delivery of the same event.
- No exception was thrown anywhere and no test failed. The bug was invisible to the automated test suite and was only discovered when a code reviewer ran the live app with `./gradlew bootRun` and polled the `outbox_event` table directly via `docker exec <postgres-container> psql -U reservation -d reservation -c "SELECT ..."` roughly every 3 seconds for about 30 seconds, watching `retry_count` sit at 0 the entire time.

## What Didn't Work

The existing `OutboxDispatcherTest` called `outboxDispatcher.dispatchOne(target.getId())` directly from the test class, using the Spring-injected proxy reference for `outboxDispatcher`. Because that call arrives from *outside* the bean, it does go through the Spring proxy, so `@Transactional` correctly applied on that path and the test passed — 13/13 green.

That test therefore validated a call path production code never actually takes. Production only ever reaches `dispatchOne` through `dispatchPendingEvents()`'s internal self-call, which — as described above — never touches the proxy and never gets a transaction. A fully green suite gave false confidence: it covered `dispatchOne` in isolation but not the scheduler's real entry point.

The underlying persistence effect of the missing transaction: with `spring.jpa.open-in-view: false` configured, there is no ambient Open-Session-in-View persistence context for the request/scheduled-task thread. Spring Data JPA's base repository methods (like `findById`) are individually transactional per call, so `outboxEventRepository.findById(...)` inside `dispatchOne`'s body opened and closed its own short-lived transaction and returned an already-detached `OutboxEvent` entity. The subsequent `event.markSuccess()` / `event.markFailure(errorMessage)` calls mutated fields on that detached entity in memory only — there was no enclosing transaction to flush the change, and no explicit `save()`/`saveAndFlush()` call anywhere in the method — so every mutation was silently discarded on return, with no exception and no log output.

## Solution

Fixed in commit `255598c`. The per-event transactional logic was extracted out of `OutboxDispatcher` into a new `@Component` bean, `OutboxEventProcessor`, so that the call from the scheduler crosses a genuine Spring proxy boundary.

`src/main/java/com/handys/reservation/service/OutboxDispatcher.java:24-35`:

```java
@Scheduled(fixedDelayString = "${outbox.poll-fixed-delay-ms:3000}")
public void dispatchPendingEvents() {
    List<OutboxEvent> pending = outboxEventRepository
            .findByStatusAndNextRetryAtLessThanEqual(OutboxStatus.PENDING, LocalDateTime.now());
    for (OutboxEvent event : pending) {
        dispatchOne(event.getId());
    }
}

public void dispatchOne(Long outboxEventId) {
    outboxEventProcessor.process(outboxEventId);
}
```

`dispatchOne` (`OutboxDispatcher.java:33-35`) no longer does any repository or client work itself — it just delegates to `outboxEventProcessor.process(outboxEventId)`, a call on a separate injected bean (`OutboxDispatcher.java:16`, constructor-injected at `OutboxDispatcher.java:18-22`).

`src/main/java/com/handys/reservation/service/OutboxEventProcessor.java:26-38`:

```java
@Transactional
public void process(Long outboxEventId) {
    OutboxEvent event = outboxEventRepository.findById(outboxEventId).orElseThrow();
    boolean simulateFailure = reservationRepository.findById(event.getReservationId())
            .map(Reservation::isSimulateDownstreamFailure)
            .orElse(false);
    try {
        downstreamClient.call(event.getEventType(), event.getReservationId(), simulateFailure);
        event.markSuccess();
    } catch (RuntimeException e) {
        event.markFailure(e.getMessage());
    }
}
```

`@Transactional` now sits on `OutboxEventProcessor.process` (`OutboxEventProcessor.java:26`), a method on a different bean than the caller. Whether the call originates from `OutboxDispatcher.dispatchOne` (internal scheduler path) or from a test holding a reference to `OutboxEventProcessor`/`OutboxDispatcher`, it always arrives at `process` through `OutboxEventProcessor`'s own proxy, so the transaction is always applied. No change was needed in `OutboxDispatcherTest`: it still calls `dispatchOne` from outside the bean, and `dispatchOne` now correctly delegates through the transactional `OutboxEventProcessor.process` call.

**Live verification performed:** posted a reservation with `simulateDownstreamFailure: true` to `POST /api/reservations`, then polled the `outbox_event` table every 3 seconds for about 30 seconds via `docker exec <postgres-container> psql -U reservation -d reservation -c "SELECT ... FROM outbox_event WHERE reservation_id=<id>"`. Observed `retry_count` climb 2 → 3 → 4 → 5, with `next_retry_at` advancing per the exponential backoff formula, then `status` transition from `PENDING` to `FAILED` once `retry_count` reached `max_retry` (5). Confirmed `GET /api/outbox-events?status=FAILED` returned the event.

## Why This Works

Spring's declarative `@Transactional` is implemented via a dynamically generated proxy (JDK dynamic proxy for interfaces, or CGLIB subclass for concrete classes) that wraps the target bean. Only calls that arrive *through* that proxy — i.e., from a different bean holding an injected reference, or from an external caller using the Spring-managed reference — are intercepted and wrapped in a transaction (or whatever the annotation specifies). A call made from inside the target object's own methods (`this.method()`, or the bare unqualified form, which is implicitly `this.method()`) goes directly to the real object and never touches the proxy, so any method-level AOP advice on that method — `@Transactional`, `@Cacheable`, `@Async`, `@Retryable`, etc. — is silently a no-op on that call path. This is one of the most well-known Spring Framework gotchas and is explicitly documented as a limitation of proxy-based AOP; AspectJ compile-time/load-time weaving does not have this limitation, but it is not Spring Boot's default AOP mode.

By moving the transactional logic into a separate bean (`OutboxEventProcessor`) and calling it as a collaborator rather than a self-invocation, every call to `process(...)` — regardless of who is calling — necessarily crosses that bean's own proxy boundary, so `@Transactional` is honored consistently.

Separately, Spring Data JPA does wrap its own generated base CRUD methods (`save`, `findById`, `deleteAll`, simple derived finders like `findByX`) in a short-lived transaction automatically, per call. That's why `outboxEventRepository.findById(...)` "worked" in the sense of returning data even outside an explicit `@Transactional` — but the returned entity was detached the instant that internal transaction closed, since there was no `open-in-view` session to keep it managed. Mutating a detached entity's fields does not get persisted unless it is explicitly re-saved or the mutation happens inside an active transaction/persistence context.

## Prevention

1. **Never call `this.someTransactionalMethod()` (or the bare unqualified form) from within the same Spring-managed bean and expect `@Transactional` or other method-level AOP annotations to apply.** If a loop, scheduler, or batch method needs to invoke transactional per-item logic, extract that logic into a separate `@Component`/bean and inject it as a collaborator, so the call crosses a real Spring proxy boundary. Concrete before/after, matching what this fix did:

   Before (broken — self-invocation inside `OutboxDispatcher`):
   ```java
   @Scheduled(...)
   public void dispatchPendingEvents() {
       for (OutboxEvent event : pending) {
           dispatchOne(event.getId()); // self-call, bypasses proxy
       }
   }

   @Transactional // silently ignored on the call above
   public void dispatchOne(Long id) { ... }
   ```

   After (fixed — cross-bean call into `OutboxEventProcessor`):
   ```java
   // OutboxDispatcher
   public void dispatchOne(Long outboxEventId) {
       outboxEventProcessor.process(outboxEventId); // crosses a real proxy
   }

   // OutboxEventProcessor (separate @Component)
   @Transactional
   public void process(Long outboxEventId) { ... }
   ```

2. **A green test suite is not proof a scheduled/background production code path works**, if the only test covering that logic invokes the method directly rather than through its actual production entry point (e.g., calling `dispatchOne()` directly instead of letting `@Scheduled dispatchPendingEvents()` drive it, as `OutboxDispatcherTest` did before this fix was diagnosed). Add at least one test that exercises the real entry point, or explicitly verify the live behavior when a change touches an AOP/transaction boundary inside a class that also self-invokes.

3. **Any Spring Data JPA repository method beyond plain `save`/`findById`/simple derived finders — especially a derived `deleteBy...`/`removeBy...` query, or any method carrying `@Lock` — should be treated as needing an explicit transaction**, either via `@Transactional` directly on the repository interface method (this works correctly; Spring Data respects method-level `@Transactional` on repository interfaces), or a transaction supplied by the caller. `RoomInventoryRepository` already follows this pattern correctly: `deleteByRoomTypeIdNotIn` is a derived bulk-delete query and is explicitly annotated `@Transactional` and `@Modifying` (`src/main/java/com/handys/reservation/repository/RoomInventoryRepository.java:25-28`), and `findForUpdate` carries `@Lock(LockModeType.PESSIMISTIC_WRITE)` (`RoomInventoryRepository.java:18-21`), which likewise needs to run inside a transaction supplied by its caller to hold the lock for the duration of the unit of work. Don't assume Spring Data's implicit per-call transaction wrapping — which does cover base CRUD methods like `save`/`findById`/`deleteAll` — extends to every custom query method.

## Related Issues

None — this is the first learning documented for this project (`docs/solutions/` did not exist before this doc).

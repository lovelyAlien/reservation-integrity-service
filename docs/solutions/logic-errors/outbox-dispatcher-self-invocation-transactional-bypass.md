---
title: Outbox 스케줄러의 @Transactional self-invocation이 Spring AOP 프록시를 우회하는 문제
date: 2026-08-04
category: logic-errors
module: outbox-dispatcher
problem_type: logic_error
component: background_job
symptoms:
  - "3초마다 도는 스케줄러가 계속 실행되는데도 outbox_event 행이 PENDING 상태에서 벗어나지 않음"
  - "매 폴링 주기마다 dispatchOne이 호출되는데도 retry_count가 전혀 증가하지 않음"
  - "스텁 다운스트림 클라이언트가 매 폴링 주기마다 무한히 재호출됨(무제한 중복 발송), bootRun으로 앱을 띄운 상태에서 psql로 outbox_event 테이블을 직접 폴링해서야 발견함"
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

# Outbox 스케줄러의 @Transactional self-invocation이 Spring AOP 프록시를 우회하는 문제

## 문제

`OutboxDispatcher`는 여전히 `PENDING` 상태인 `outbox_event` 행을 재시도하는 `@Scheduled` 폴러다. 이벤트별 작업 — 이벤트와 관련 예약을 조회하고, 다운스트림 클라이언트를 호출하고, 이벤트를 성공/실패로 표시하는 일 — 은 상태 변경(`retry_count`, `status`, `next_retry_at`)이 실제로 저장되도록 DB 트랜잭션 안에서 실행돼야 한다.

수정 전에는 `dispatchPendingEvents()`(스케줄 진입점, 그 자체는 `@Transactional`이 아님)가 대기 중인 이벤트를 순회하며 자기 자신의 `dispatchOne(event.getId())`를 호출했다 — 같은 클래스, 같은 빈 안에서의 메서드 호출이다. `dispatchOne`에는 `@Transactional`이 붙어 있었지만, 이 호출 경로에서는 그 애노테이션이 전혀 적용되지 않았다. Spring은 호출이 빈의 Spring 관리 프록시를 거쳐 들어올 때만 메서드 레벨 AOP를 적용하기 때문이다. 빈 자신의 코드 안에서 이루어진 호출(`this.dispatchOne(...)` 또는 수식어 없는 형태)은 프록시를 거치지 않고 대상 객체로 곧장 가버리며, 그 결과 트랜잭션도 완전히 우회된다.

## 증상

- 실행 중인 애플리케이션에서 `outbox_event` 행이 `PENDING`을 벗어나지 못했다.
- 앱을 아무리 오래 돌려도 `retry_count`가 증가하지 않았다.
- 스텁 다운스트림 클라이언트가 폴링 주기(3초)마다 계속 호출됐다 — 같은 이벤트가 무제한으로 중복 발송된 것이다.
- 어디서도 예외가 발생하지 않았고 어떤 테스트도 실패하지 않았다. 이 버그는 자동화된 테스트 스위트에는 보이지 않았고, 코드 리뷰어가 `./gradlew bootRun`으로 실제 앱을 띄운 뒤 `docker exec <postgres-container> psql -U reservation -d reservation -c "SELECT ..."`로 `outbox_event` 테이블을 3초 간격으로 약 30초간 직접 폴링해 `retry_count`가 내내 0에 머무는 것을 지켜보고서야 발견됐다.

## 시도했지만 안 됐던 것

기존 `OutboxDispatcherTest`는 테스트 클래스에서 Spring이 주입한 `outboxDispatcher` 프록시 참조를 통해 `outboxDispatcher.dispatchOne(target.getId())`를 직접 호출했다. 이 호출은 빈 *바깥*에서 오기 때문에 실제로 Spring 프록시를 거치고, 그래서 이 경로에서는 `@Transactional`이 정상적으로 적용돼 테스트가 통과했다 — 13/13 그린이었다.

즉 이 테스트는 프로덕션 코드가 실제로는 절대 타지 않는 호출 경로를 검증한 셈이다. 프로덕션에서는 `dispatchPendingEvents()`의 내부 self-call을 통해서만 `dispatchOne`에 도달하는데, 앞서 설명했듯 이 경로는 프록시를 전혀 거치지 않고 트랜잭션도 걸리지 않는다. 전부 초록불인 테스트 스위트가 거짓 안도감을 준 것이다 — `dispatchOne`을 단독으로는 검증했지만, 스케줄러의 실제 진입점은 검증하지 못했다.

트랜잭션이 없을 때 실제로 벌어지는 영속성 문제: `spring.jpa.open-in-view: false`가 설정돼 있어 요청/스케줄 작업 스레드에 주변(ambient) Open-Session-in-View 영속성 컨텍스트가 없다. Spring Data JPA의 기본 리포지토리 메서드(`findById` 등)는 호출마다 개별적으로 트랜잭션이 걸리므로, `dispatchOne` 본문 안의 `outboxEventRepository.findById(...)`는 자신만의 짧은 트랜잭션을 열고 닫은 뒤 이미 detach된 `OutboxEvent` 엔티티를 반환했다. 이어지는 `event.markSuccess()` / `event.markFailure(errorMessage)` 호출은 이 detach된 엔티티의 필드를 메모리상에서만 변경했다 — 변경 사항을 flush할 감싸는 트랜잭션이 없었고, 메서드 어디에도 명시적인 `save()`/`saveAndFlush()` 호출이 없었다 — 그래서 모든 변경이 예외도, 로그도 없이 조용히 사라졌다.

## 해결

커밋 `255598c`에서 수정했다. 이벤트별 트랜잭션 로직을 `OutboxDispatcher`에서 떼어내 새로운 `@Component` 빈 `OutboxEventProcessor`로 옮겨서, 스케줄러의 호출이 실제 Spring 프록시 경계를 넘도록 만들었다.

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

`dispatchOne`(`OutboxDispatcher.java:33-35`)은 이제 리포지토리나 클라이언트 작업을 스스로 하지 않는다 — 별도로 주입된 빈(`OutboxDispatcher.java:16`, `OutboxDispatcher.java:18-22`에서 생성자 주입)의 `outboxEventProcessor.process(outboxEventId)`로 위임만 한다.

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

이제 `@Transactional`은 호출자와는 다른 빈의 메서드인 `OutboxEventProcessor.process`(`OutboxEventProcessor.java:26`)에 붙어 있다. 호출이 `OutboxDispatcher.dispatchOne`(스케줄러 내부 경로)에서 오든, `OutboxEventProcessor`/`OutboxDispatcher` 참조를 든 테스트에서 오든, `process`에는 항상 `OutboxEventProcessor` 자신의 프록시를 거쳐 도달하므로 트랜잭션이 항상 적용된다. `OutboxDispatcherTest`는 수정할 필요가 없었다 — 여전히 빈 바깥에서 `dispatchOne`을 호출하고, `dispatchOne`은 이제 트랜잭션이 걸린 `OutboxEventProcessor.process` 호출로 올바르게 위임한다.

**실제 라이브 검증:** `simulateDownstreamFailure: true`로 `POST /api/reservations`를 호출해 예약을 생성한 뒤, `docker exec <postgres-container> psql -U reservation -d reservation -c "SELECT ... FROM outbox_event WHERE reservation_id=<id>"`로 `outbox_event` 테이블을 3초 간격으로 약 30초간 폴링했다. `retry_count`가 2 → 3 → 4 → 5로 실제 증가했고, `next_retry_at`도 지수 백오프 공식에 따라 갱신됐으며, `retry_count`가 `max_retry`(5)에 도달하자 `status`가 `PENDING`에서 `FAILED`로 전이됐다. `GET /api/outbox-events?status=FAILED`가 해당 이벤트를 정상적으로 반환하는 것도 확인했다.

## 왜 이렇게 고치면 해결되는가

Spring의 선언적 `@Transactional`은 대상 빈을 감싸는 동적 생성 프록시(인터페이스면 JDK dynamic proxy, 구체 클래스면 CGLIB 서브클래스)로 구현된다. 이 프록시를 *통과해서* 들어오는 호출 — 즉 주입된 참조를 가진 다른 빈에서 오거나, Spring이 관리하는 참조를 쓰는 외부 호출자에서 오는 경우 — 만 인터셉트되어 트랜잭션(또는 애노테이션이 지정한 다른 동작)으로 감싸진다. 대상 객체 자신의 메서드 안에서 이루어진 호출(`this.method()`, 또는 암묵적으로 `this.method()`와 같은 수식어 없는 호출)은 프록시를 거치지 않고 실제 객체로 곧장 가므로, 그 메서드에 걸린 `@Transactional`, `@Cacheable`, `@Async`, `@Retryable` 등 어떤 메서드 레벨 AOP도 그 호출 경로에서는 조용히 무시된다. 이는 Spring Framework에서 가장 잘 알려진 함정 중 하나이며 프록시 기반 AOP의 한계로 공식 문서에도 명시돼 있다. AspectJ 컴파일 타임/로드 타임 위빙에는 이 제약이 없지만, 이는 Spring Boot의 기본 AOP 방식이 아니다.

트랜잭션 로직을 별도 빈(`OutboxEventProcessor`)으로 옮기고 이를 협력 객체로서 호출하도록 바꾸면, 누가 호출하든 `process(...)` 호출은 반드시 그 빈 자신의 프록시 경계를 넘게 되므로 `@Transactional`이 일관되게 적용된다.

별개로, Spring Data JPA는 자체 생성한 기본 CRUD 메서드(`save`, `findById`, `deleteAll`, `findByX` 같은 단순 파생 조회 메서드)는 호출마다 자동으로 짧은 트랜잭션으로 감싼다. `outboxEventRepository.findById(...)`가 명시적 `@Transactional` 없이도 데이터를 반환한다는 점에서는 "동작"한 이유가 여기 있다 — 다만 그 순간 내부 트랜잭션이 닫히면서 반환된 엔티티는 곧바로 detach됐다. `open-in-view` 세션이 없어 관리 상태를 유지해주지 않았기 때문이다. detach된 엔티티의 필드를 변경해도, 명시적으로 재저장하거나 활성 트랜잭션/영속성 컨텍스트 안에서 변경이 이루어지지 않는 한 저장되지 않는다.

## 재발 방지

1. **같은 Spring 관리 빈 안에서 `this.someTransactionalMethod()`(또는 수식어 없는 형태)를 호출하면서 `@Transactional`이나 다른 메서드 레벨 AOP 애노테이션이 적용될 거라고 기대하지 말 것.** 루프, 스케줄러, 배치 메서드가 트랜잭션이 필요한 개별 로직을 호출해야 한다면, 그 로직을 별도의 `@Component`/빈으로 추출해 협력 객체로 주입해서 호출이 실제 Spring 프록시 경계를 넘도록 해야 한다. 이번 수정과 동일한 구체적인 before/after:

   Before (깨진 상태 — `OutboxDispatcher` 내부 self-invocation):
   ```java
   @Scheduled(...)
   public void dispatchPendingEvents() {
       for (OutboxEvent event : pending) {
           dispatchOne(event.getId()); // self-call, 프록시를 우회함
       }
   }

   @Transactional // 위 호출 경로에서는 조용히 무시됨
   public void dispatchOne(Long id) { ... }
   ```

   After (수정 후 — `OutboxEventProcessor`로의 cross-bean 호출):
   ```java
   // OutboxDispatcher
   public void dispatchOne(Long outboxEventId) {
       outboxEventProcessor.process(outboxEventId); // 실제 프록시를 통과함
   }

   // OutboxEventProcessor (별도 @Component)
   @Transactional
   public void process(Long outboxEventId) { ... }
   ```

2. **초록불 테스트 스위트가 스케줄/백그라운드 프로덕션 코드 경로가 실제로 동작한다는 증거는 아니다**, 그 로직을 커버하는 유일한 테스트가 실제 프로덕션 진입점이 아니라 메서드를 직접 호출하고 있다면 더더욱 그렇다 (이번 버그가 진단되기 전 `OutboxDispatcherTest`가 그랬듯, `@Scheduled dispatchPendingEvents()`가 구동하게 두는 대신 `dispatchOne()`을 직접 호출하는 경우). 실제 진입점을 거치는 테스트를 최소 하나는 추가하거나, self-invocation이 있는 클래스 안에서 AOP/트랜잭션 경계를 건드리는 변경이 생기면 라이브 동작을 명시적으로 검증해야 한다.

3. **단순 `save`/`findById`/단순 파생 조회 메서드를 넘어서는 Spring Data JPA 리포지토리 메서드 — 특히 파생 `deleteBy...`/`removeBy...` 쿼리나 `@Lock`이 붙은 메서드 — 는 명시적 트랜잭션이 필요하다고 간주해야 한다**, 리포지토리 인터페이스 메서드에 직접 `@Transactional`을 붙이거나(이 방식은 정상 동작한다 — Spring Data는 리포지토리 인터페이스의 메서드 레벨 `@Transactional`을 존중한다) 호출자가 트랜잭션을 제공해야 한다. `RoomInventoryRepository`는 이미 이 패턴을 올바르게 따르고 있다: `deleteByRoomTypeIdNotIn`은 파생 벌크 삭제 쿼리이며 명시적으로 `@Transactional`과 `@Modifying`이 붙어 있고(`src/main/java/com/handys/reservation/repository/RoomInventoryRepository.java:25-28`), `findForUpdate`는 `@Lock(LockModeType.PESSIMISTIC_WRITE)`가 붙어 있는데(`RoomInventoryRepository.java:18-21`) 이 역시 작업 단위 동안 락을 유지하려면 호출자가 제공하는 트랜잭션 안에서 실행돼야 한다. Spring Data의 암묵적인 호출별 트랜잭션 래핑 — `save`/`findById`/`deleteAll` 같은 기본 CRUD 메서드는 해당되지만 — 이 커스텀 쿼리 메서드 전부에 적용된다고 가정하지 말 것.

## 관련 이슈

없음 — 이 프로젝트에서 처음 기록하는 학습 문서다 (이 문서가 생기기 전에는 `docs/solutions/`가 없었다).

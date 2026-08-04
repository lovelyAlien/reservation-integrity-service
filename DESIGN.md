# 설계 문서

> 문제 정의는 [PROBLEM.md](./PROBLEM.md) 참고. 이 문서는 그 문제를 어떻게 구현할지를 다룬다.

## 기술 스택

- Java 21, Spring Boot 3.3.x, Gradle(Groovy DSL)
- Spring Data JPA + PostgreSQL (docker-compose로 로컬 기동)
- Flyway (스키마 마이그레이션 + 초기 시드 데이터)
- JUnit5 + `@SpringBootTest` (동시성 통합 테스트는 실제 Postgres 필요)

PostgreSQL을 Testcontainers 대신 docker-compose로 직접 띄우기로 한 이유: 이 과제의 핵심은 "DB 락이 실제로 오버부킹을 막는다"는 것을 증명하는 것이라 H2 대신 실제 프로덕션급 DB가 필요했고, Testcontainers보다 docker-compose가 재현 방법을 README에서 보여주기 쉽다고 판단했다. 트레이드오프: 테스트 실행 전 `docker-compose up -d`를 수동으로 먼저 실행해야 한다 (README에 명시).

## 데이터 모델

```sql
-- 룸타입 x 날짜 단위 재고. 연박 재고 계산은 범위 밖이므로 날짜별 단일 재고로 단순화.
CREATE TABLE room_inventory (
    id               BIGSERIAL PRIMARY KEY,
    room_type_id     VARCHAR(50)  NOT NULL,
    stay_date        DATE         NOT NULL,
    total_stock      INT          NOT NULL,
    available_stock  INT          NOT NULL,
    updated_at       TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_room_inventory UNIQUE (room_type_id, stay_date)
);

-- 예약. (channel, external_reservation_id) 유니크 제약이 멱등성의 최종 방어선.
-- simulate_downstream_failure: 실제 도어락/정산 시스템이 없는 상태에서 outbox 재시도 로직을
-- 테스트/데모로 검증하기 위한 필드 (API 명세의 simulateDownstreamFailure와 대응).
CREATE TABLE reservation (
    id                           BIGSERIAL PRIMARY KEY,
    channel                      VARCHAR(30)  NOT NULL,
    external_reservation_id      VARCHAR(100) NOT NULL,
    room_type_id                 VARCHAR(50)  NOT NULL,
    stay_date                    DATE         NOT NULL,
    status                       VARCHAR(20)  NOT NULL,
    simulate_downstream_failure  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at                   TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_reservation_channel_external UNIQUE (channel, external_reservation_id)
);

-- 예약 확정 후 후속 처리(도어락 발급, 정산 트리거)를 위한 Transactional Outbox.
CREATE TABLE outbox_event (
    id              BIGSERIAL PRIMARY KEY,
    reservation_id  BIGINT       NOT NULL REFERENCES reservation(id),
    event_type      VARCHAR(30)  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count     INT          NOT NULL DEFAULT 0,
    max_retry       INT          NOT NULL DEFAULT 5,
    next_retry_at   TIMESTAMP    NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT now()
);
```

## API

| Method | Path                         | 설명                                    |
|--------|------------------------------|-----------------------------------------|
| POST   | `/api/reservations`          | OTA 예약 확정 웹훅 시뮬레이션           |
| GET    | `/api/reservations/{id}`     | 예약 조회                               |
| POST   | `/api/room-inventories`      | 재고 시딩 (테스트/데모용, 인증 없음)     |
| GET    | `/api/outbox-events?status=` | 후속 처리 상태 조회 (FAILED 추적용)     |

`POST /api/reservations` 요청 예:

```json
{
  "channel": "AIRBNB",
  "externalReservationId": "AB-12345",
  "roomTypeId": "DELUXE_A",
  "stayDate": "2026-08-10",
  "simulateDownstreamFailure": false
}
```

응답:

| 상황                                   | HTTP | errorCode          |
|----------------------------------------|------|---------------------|
| 신규 예약 확정                         | 201  | -                    |
| 같은 웹훅 재전송(순차)                 | 200  | ALREADY_PROCESSED    |
| 존재하지 않는 room_type/date           | 404  | ROOM_NOT_FOUND        |
| 재고 소진                              | 409  | SOLD_OUT              |
| 같은 웹훅 동시 재전송(유니크 제약 충돌)| 409  | DUPLICATE_CONFLICT    |

`simulateDownstreamFailure`는 실제 도어락/정산 시스템이 없는 상태에서 outbox 재시도·최종 실패 추적 로직이 실제로 동작함을 테스트/데모에서 증명하기 위한 필드다.

## 동시성 및 멱등성 처리

`ReservationService.reserve()`는 하나의 트랜잭션 안에서 다음 순서로 처리한다.

1. `room_inventory` 행을 `SELECT ... FOR UPDATE`로 잠근다 (없으면 `ROOM_NOT_FOUND` 404, 즉시 종료).
2. `(channel, externalReservationId)`로 기존 예약을 조회한다 — 있으면 `ALREADY_PROCESSED` 200으로 종료.
3. `available_stock > 0`을 확인한다 — 0이면 `SOLD_OUT` 409.
4. 재고를 차감하고, `reservation`을 insert하고, 같은 트랜잭션 안에서 `outbox_event` 2건(DOOR_LOCK_ISSUE, SETTLEMENT_TRIGGER)을 insert한다.
5. 4번의 insert가 유니크 제약(`uq_reservation_channel_external`)에 걸리면 — 서로 다른 재고 행을 잠근 두 트랜잭션이 동시에 재전송된 경우에만 발생 가능 — `DataIntegrityViolationException`을 잡아 `DUPLICATE_CONFLICT` 409로 변환한다.
6. 커밋 시점에 락이 해제되며 대기 중이던 다음 트랜잭션이 순서대로 진행된다.

이 흐름은 [PROBLEM.md](./PROBLEM.md)의 "순차 재전송"(2번에서 걸림) / "동시 재전송"(5번에서 걸림) 시나리오 설명과 정확히 대응한다.

**락 전략 선택: 비관적 락(`SELECT ... FOR UPDATE`)**

낙관적 락(버전 컬럼)도 올바르게 구현하면 오버부킹을 막을 수 있지만, `UPDATE ... WHERE version=?`에서 비즈니스 조건(`stock > 0`)을 함께 걸지 않거나 재시도 로직이 실패 케이스를 제대로 처리하지 못하면 재고가 음수로 내려가는 버그가 생기기 쉽다. 이 과제는 "정확히 하나만 성공"을 절대적으로 보장해야 하므로, 재시도 로직 없이 요청을 순차 직렬화하는 비관적 락이 구조적으로 더 안전하다고 판단했다.

## Outbox 재시도

- `@Scheduled` 폴러가 `status = PENDING AND next_retry_at <= now()`인 이벤트를 배치로 조회한다.
- 각 이벤트에 대해 스텁 `DownstreamClient`를 호출한다 (로그로 시뮬레이션; 원본 예약의 `simulateDownstreamFailure=true`면 강제로 예외 발생).
- 실패 시 `retry_count`를 증가시키고 exponential backoff(`next_retry_at = now() + min(2^retry_count, 60)초`)로 `next_retry_at`을 갱신한다.
- `retry_count >= max_retry`에 도달하면 `status = FAILED`로 마감하고 더 이상 폴링하지 않는다. `GET /api/outbox-events?status=FAILED`로 운영자가 추적할 수 있다.

## 테스트 전략

1. **단위 테스트**: 재고 체크/멱등성 분기 로직 (Mockito로 리포지토리 목킹).
2. **동시성 통합 테스트** (실제 Postgres 필요): 재고 1개인 room_type에 서로 다른 `externalReservationId`로 N개 요청을 `ExecutorService` + `CountDownLatch`로 동시에 보내 정확히 1건만 `CONFIRMED`인지 검증.
3. **멱등성 통합 테스트**: 같은 `(channel, externalReservationId)`로 N개 요청을 동시에 보내 정확히 1건만 예약으로 반영되는지 검증 (나머지는 `ALREADY_PROCESSED` 또는 `DUPLICATE_CONFLICT`).
4. **Outbox 재시도 테스트**: `simulateDownstreamFailure=true`로 예약 생성 → 재시도 횟수 증가 → `max_retry` 도달 후 `FAILED` 전이 검증.

테스트 실행 전 `docker-compose up -d`로 로컬 Postgres를 먼저 기동해야 한다.

## 범위 판단

([PROBLEM.md](./PROBLEM.md)에서 참조하는 절)

| 제외 항목 | 배제 이유 |
|---|---|
| 실제 OTA API/웹훅 연동 | 시간 내 실제 OTA 연동 자격/스펙 확보가 불가능하고, 핵심 평가 포인트는 "동시성·멱등성 처리 로직"이지 실제 OTA 프로토콜 구현이 아니다. |
| 프론트엔드/어드민 화면 | 이 과제가 검증하려는 문제(재고 정합성)는 백엔드 로직에만 존재한다. UI는 문제 해결에 기여하지 않는다. |
| 실제 도어락/정산 연동 | 외부 시스템 의존성 없이도 "재시도·최종 실패 추적" 로직 자체는 스텁으로 충분히 검증 가능하다. |
| 연박 재고 계산, 요금 계산, 인증/인가 | 재고 정합성 문제(경쟁조건, 멱등성)와 직교하는 별개의 관심사로, 함께 다루면 시간 내 핵심 로직을 깊이 검증하기 어렵다. |
| 다중 인스턴스 분산락 | 단일 인스턴스 DB 락만으로도 오버부킹 방지의 핵심 메커니즘 검증에는 충분하다. Redis 등 분산락 도입은 별도의 인프라·장애 시나리오 검증이 필요해 범위를 벗어난다. |
| 재전송 시 페이로드가 달라지는 경우의 데이터 정합성 복구 | 재요청은 정의상 같은 예약을 가리켜야 하며, 다르다면 OTA 측 데이터 오류다. 이 경우 API는 `DUPLICATE_CONFLICT`(동시) 또는 기존 예약 매칭(순차)까지만 방어하고, 그 이상의 자동 복구는 다루지 않는다. |

## AI 협업 과정

이 설계는 Claude(Claude Code)와의 대화를 통해 다듬었다. 주요 판단 사례:

- **낙관적 락 vs 비관적 락**: 처음에는 두 가지 옵션을 모두 검토했다. AI에게 "낙관적 락은 오버부킹을 허용할 수 있는가"를 직접 질문해 검증한 결과, 낙관적 락 자체의 결함이 아니라 "재시도 시 비즈니스 조건(`stock > 0`)을 WHERE절에 함께 걸지 않으면 재고가 음수가 될 수 있다"는 구현 리스크가 핵심이라는 걸 확인했다. 이 과제는 "정확히 하나만 성공"을 절대 보장해야 하므로, 재시도 로직 없이 구조적으로 안전한 비관적 락을 최종 채택했다.
- **DB 선택**: H2(인메모리)는 설정이 빠르지만 락/트랜잭션 동작이 PostgreSQL과 미묘하게 달라 "진짜 동시성 방어"라는 과제 핵심 주장의 설득력이 떨어질 수 있다고 판단해, 실제 PostgreSQL(docker-compose)로 검증하기로 했다.
- **다운스트림 실패 시뮬레이션**: 실제 도어락/정산 시스템이 없는 상황에서 재시도·최종 실패 추적 로직이 실제로 동작함을 어떻게 증명할지 논의했고, 요청 페이로드에 명시적 테스트 플래그(`simulateDownstreamFailure`)를 두는 방식으로 결정했다.

구현 단계에서 AI가 생성한 코드에 대한 구체적 검토/수정 사례는 계속 이 절에 추가한다.

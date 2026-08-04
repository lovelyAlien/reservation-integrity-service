# 예약 정합성 서비스 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 여러 OTA 채널에서 동시에 들어오는 예약 웹훅을 오버부킹 없이, 중복 없이 안전하게 반영하고, 후속 처리 실패를 재시도·추적 가능하게 만드는 Spring Boot 백엔드 서비스를 구현한다.

**Architecture:** Spring Boot(Java 21) + PostgreSQL. `ReservationService.reserve()`가 `SELECT ... FOR UPDATE`로 재고 행을 잠근 뒤 멱등성 체크 → 재고 확인 → 예약 insert(유니크 제약) → outbox 이벤트 insert를 한 트랜잭션에서 처리한다. 별도 `@Scheduled` 폴러(`OutboxDispatcher`)가 outbox 이벤트를 읽어 스텁 다운스트림 클라이언트를 호출하고, 실패 시 재시도·최종 실패 전이를 관리한다.

**Tech Stack:** Java 21, Spring Boot 3.3.x, Spring Data JPA, PostgreSQL 16(docker-compose), Flyway, Gradle(Groovy DSL, wrapper 사용), JUnit5 + AssertJ + Mockito(spring-boot-starter-test 포함)

## Global Constraints

- Java 21 (LTS), Spring Boot 3.3.x, PostgreSQL 16
- 통합 테스트 실행 전 반드시 `docker-compose up -d`로 로컬 Postgres를 기동해야 한다 (Testcontainers 미사용)
- 커밋 메시지는 한글로 작성하고 `Co-Authored-By` 등 AI 출처 서명을 포함하지 않는다 — `.githooks/commit-msg`가 이를 강제한다 (최초 1회 `git config core.hooksPath .githooks` 필요, 이미 설정됨)
- 패키지 루트: `com.handys.reservation`
- 연박 재고 계산, 요금 계산, 인증/인가, 다중 인스턴스 분산락은 범위 밖이다 ([DESIGN.md](../../../DESIGN.md) "범위 판단" 참고)

---

### Task 1: 프로젝트 스캐폴딩 (Gradle, docker-compose, Flyway, 애플리케이션 부트스트랩)

**Files:**
- Create: `settings.gradle`
- Create: `build.gradle`
- Create: `docker-compose.yml`
- Create: `src/main/resources/application.yml`
- Create: `src/main/resources/db/migration/V1__init.sql`
- Create: `src/main/resources/db/migration/V2__seed_data.sql`
- Create: `src/main/java/com/handys/reservation/ReservationIntegrityServiceApplication.java`
- Create: `gradlew`, `gradlew.bat`, `gradle/wrapper/*` (via `gradle wrapper` 명령)

**Interfaces:**
- Produces: 실행 가능한 Spring Boot 애플리케이션 스켈레톤, Flyway로 관리되는 스키마(`room_inventory`, `reservation`, `outbox_event`), `docker-compose.yml`의 Postgres 서비스(포트 5432, db/user/password 모두 `reservation`)

이 태스크는 순수 스캐폴딩이라 TDD 사이클 대신 "기동 확인"으로 검증한다.

- [ ] **Step 1: `settings.gradle` 작성**

```groovy
rootProject.name = 'reservation-integrity-service'
```

- [ ] **Step 2: `build.gradle` 작성**

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.3.5'
    id 'io.spring.dependency-management' version '1.1.6'
}

group = 'com.handys'
version = '0.0.1-SNAPSHOT'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.flywaydb:flyway-core'
    implementation 'org.flywaydb:flyway-database-postgresql'
    runtimeOnly 'org.postgresql:postgresql'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
}

tasks.named('test') {
    useJUnitPlatform()
}
```

- [ ] **Step 3: `docker-compose.yml` 작성**

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: reservation
      POSTGRES_USER: reservation
      POSTGRES_PASSWORD: reservation
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U reservation"]
      interval: 5s
      timeout: 3s
      retries: 5
```

- [ ] **Step 4: `src/main/resources/application.yml` 작성**

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/reservation
    username: reservation
    password: reservation
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true

outbox:
  poll-fixed-delay-ms: 3000
```

- [ ] **Step 5: Flyway 마이그레이션 작성 — `src/main/resources/db/migration/V1__init.sql`**

```sql
CREATE TABLE room_inventory (
    id               BIGSERIAL PRIMARY KEY,
    room_type_id     VARCHAR(50)  NOT NULL,
    stay_date        DATE         NOT NULL,
    total_stock      INT          NOT NULL,
    available_stock  INT          NOT NULL,
    updated_at       TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_room_inventory UNIQUE (room_type_id, stay_date)
);

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

- [ ] **Step 6: 시드 데이터 작성 — `src/main/resources/db/migration/V2__seed_data.sql`**

```sql
INSERT INTO room_inventory (room_type_id, stay_date, total_stock, available_stock) VALUES
    ('DELUXE_A', '2026-08-10', 5, 5),
    ('STANDARD_B', '2026-08-10', 3, 3);
```

- [ ] **Step 7: 메인 애플리케이션 클래스 — `src/main/java/com/handys/reservation/ReservationIntegrityServiceApplication.java`**

```java
package com.handys.reservation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ReservationIntegrityServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReservationIntegrityServiceApplication.class, args);
    }
}
```

- [ ] **Step 8: Gradle wrapper 생성**

Run: `gradle wrapper --gradle-version 8.10.2`
Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` 생성됨

- [ ] **Step 9: DB 기동 후 애플리케이션 부팅 확인**

Run:
```bash
docker-compose up -d
./gradlew bootRun
```
Expected: 콘솔에 `Started ReservationIntegrityServiceApplication` 로그가 뜨고, Flyway 로그에 `Successfully applied 2 migrations`가 출력된다. `Ctrl+C`로 종료.

- [ ] **Step 10: Commit**

```bash
git add settings.gradle build.gradle docker-compose.yml gradlew gradlew.bat gradle .gitignore \
  src/main/resources/application.yml src/main/resources/db/migration \
  src/main/java/com/handys/reservation/ReservationIntegrityServiceApplication.java
git commit -m "$(printf '프로젝트 스캐폴딩: Gradle, Flyway, docker-compose 설정\n\nSpring Boot 애플리케이션 뼈대와 room_inventory/reservation/outbox_event 스키마를 구성한다.')"
```

---

### Task 2: 도메인 엔티티와 리포지토리

**Files:**
- Create: `src/main/java/com/handys/reservation/domain/RoomInventory.java`
- Create: `src/main/java/com/handys/reservation/domain/Reservation.java`
- Create: `src/main/java/com/handys/reservation/domain/ReservationStatus.java`
- Create: `src/main/java/com/handys/reservation/domain/OutboxEvent.java`
- Create: `src/main/java/com/handys/reservation/domain/OutboxEventType.java`
- Create: `src/main/java/com/handys/reservation/domain/OutboxStatus.java`
- Create: `src/main/java/com/handys/reservation/repository/RoomInventoryRepository.java`
- Create: `src/main/java/com/handys/reservation/repository/ReservationRepository.java`
- Create: `src/main/java/com/handys/reservation/repository/OutboxEventRepository.java`
- Test: `src/test/java/com/handys/reservation/repository/RepositorySmokeTest.java`

**Interfaces:**
- Consumes: Task 1의 Flyway 스키마(`room_inventory`, `reservation`, `outbox_event`)
- Produces:
  - `RoomInventory(String roomTypeId, LocalDate stayDate, int totalStock)`, `hasStock()`, `decrease()`, `getAvailableStock()`
  - `Reservation(String channel, String externalReservationId, String roomTypeId, LocalDate stayDate, boolean simulateDownstreamFailure)`, `getId()`, `isSimulateDownstreamFailure()`
  - `OutboxEvent(Long reservationId, OutboxEventType eventType)`, `markSuccess()`, `markFailure(String error)`, `isExhausted()`, `getMaxRetry()`
  - `RoomInventoryRepository.findForUpdate(String roomTypeId, LocalDate stayDate): Optional<RoomInventory>` (`SELECT ... FOR UPDATE`)
  - `ReservationRepository.findByChannelAndExternalReservationId(String, String): Optional<Reservation>`
  - `OutboxEventRepository.findByStatusAndNextRetryAtLessThanEqual(OutboxStatus, LocalDateTime): List<OutboxEvent>`, `findByStatus(OutboxStatus): List<OutboxEvent>`, `findByReservationId(Long): List<OutboxEvent>`

- [ ] **Step 1: 엔티티 작성 — `src/main/java/com/handys/reservation/domain/ReservationStatus.java`**

```java
package com.handys.reservation.domain;

public enum ReservationStatus {
    CONFIRMED
}
```

- [ ] **Step 2: `src/main/java/com/handys/reservation/domain/OutboxEventType.java`**

```java
package com.handys.reservation.domain;

public enum OutboxEventType {
    DOOR_LOCK_ISSUE,
    SETTLEMENT_TRIGGER
}
```

- [ ] **Step 3: `src/main/java/com/handys/reservation/domain/OutboxStatus.java`**

```java
package com.handys.reservation.domain;

public enum OutboxStatus {
    PENDING,
    SUCCESS,
    FAILED
}
```

- [ ] **Step 4: `src/main/java/com/handys/reservation/domain/RoomInventory.java`**

```java
package com.handys.reservation.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "room_inventory")
public class RoomInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_type_id", nullable = false, length = 50)
    private String roomTypeId;

    @Column(name = "stay_date", nullable = false)
    private LocalDate stayDate;

    @Column(name = "total_stock", nullable = false)
    private int totalStock;

    @Column(name = "available_stock", nullable = false)
    private int availableStock;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    protected RoomInventory() {
    }

    public RoomInventory(String roomTypeId, LocalDate stayDate, int totalStock) {
        this.roomTypeId = roomTypeId;
        this.stayDate = stayDate;
        this.totalStock = totalStock;
        this.availableStock = totalStock;
    }

    public boolean hasStock() {
        return availableStock > 0;
    }

    public void decrease() {
        if (!hasStock()) {
            throw new IllegalStateException(
                    "no stock left for roomTypeId=" + roomTypeId + ", stayDate=" + stayDate);
        }
        this.availableStock -= 1;
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getRoomTypeId() { return roomTypeId; }
    public LocalDate getStayDate() { return stayDate; }
    public int getTotalStock() { return totalStock; }
    public int getAvailableStock() { return availableStock; }
}
```

- [ ] **Step 5: `src/main/java/com/handys/reservation/domain/Reservation.java`**

```java
package com.handys.reservation.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "reservation")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String channel;

    @Column(name = "external_reservation_id", nullable = false, length = 100)
    private String externalReservationId;

    @Column(name = "room_type_id", nullable = false, length = 50)
    private String roomTypeId;

    @Column(name = "stay_date", nullable = false)
    private LocalDate stayDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "simulate_downstream_failure", nullable = false)
    private boolean simulateDownstreamFailure;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected Reservation() {
    }

    public Reservation(String channel, String externalReservationId, String roomTypeId,
                        LocalDate stayDate, boolean simulateDownstreamFailure) {
        this.channel = channel;
        this.externalReservationId = externalReservationId;
        this.roomTypeId = roomTypeId;
        this.stayDate = stayDate;
        this.status = ReservationStatus.CONFIRMED;
        this.simulateDownstreamFailure = simulateDownstreamFailure;
    }

    public Long getId() { return id; }
    public String getChannel() { return channel; }
    public String getExternalReservationId() { return externalReservationId; }
    public String getRoomTypeId() { return roomTypeId; }
    public LocalDate getStayDate() { return stayDate; }
    public ReservationStatus getStatus() { return status; }
    public boolean isSimulateDownstreamFailure() { return simulateDownstreamFailure; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 6: `src/main/java/com/handys/reservation/domain/OutboxEvent.java`**

```java
package com.handys.reservation.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private OutboxEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "max_retry", nullable = false)
    private int maxRetry = 5;

    @Column(name = "next_retry_at", nullable = false)
    private LocalDateTime nextRetryAt = LocalDateTime.now();

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    protected OutboxEvent() {
    }

    public OutboxEvent(Long reservationId, OutboxEventType eventType) {
        this.reservationId = reservationId;
        this.eventType = eventType;
    }

    public boolean isExhausted() {
        return retryCount >= maxRetry;
    }

    public void markSuccess() {
        this.status = OutboxStatus.SUCCESS;
        this.updatedAt = LocalDateTime.now();
    }

    public void markFailure(String error) {
        this.retryCount += 1;
        this.lastError = error;
        this.updatedAt = LocalDateTime.now();
        if (isExhausted()) {
            this.status = OutboxStatus.FAILED;
        } else {
            long backoffSeconds = Math.min((long) Math.pow(2, retryCount), 60);
            this.nextRetryAt = LocalDateTime.now().plusSeconds(backoffSeconds);
        }
    }

    public Long getId() { return id; }
    public Long getReservationId() { return reservationId; }
    public OutboxEventType getEventType() { return eventType; }
    public OutboxStatus getStatus() { return status; }
    public int getRetryCount() { return retryCount; }
    public int getMaxRetry() { return maxRetry; }
    public LocalDateTime getNextRetryAt() { return nextRetryAt; }
    public String getLastError() { return lastError; }
}
```

- [ ] **Step 7: 리포지토리 작성 — `src/main/java/com/handys/reservation/repository/RoomInventoryRepository.java`**

```java
package com.handys.reservation.repository;

import com.handys.reservation.domain.RoomInventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface RoomInventoryRepository extends JpaRepository<RoomInventory, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RoomInventory r where r.roomTypeId = :roomTypeId and r.stayDate = :stayDate")
    Optional<RoomInventory> findForUpdate(@Param("roomTypeId") String roomTypeId,
                                           @Param("stayDate") LocalDate stayDate);
}
```

- [ ] **Step 8: `src/main/java/com/handys/reservation/repository/ReservationRepository.java`**

```java
package com.handys.reservation.repository;

import com.handys.reservation.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    Optional<Reservation> findByChannelAndExternalReservationId(String channel, String externalReservationId);
}
```

- [ ] **Step 9: `src/main/java/com/handys/reservation/repository/OutboxEventRepository.java`**

```java
package com.handys.reservation.repository;

import com.handys.reservation.domain.OutboxEvent;
import com.handys.reservation.domain.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByStatusAndNextRetryAtLessThanEqual(OutboxStatus status, LocalDateTime now);

    List<OutboxEvent> findByStatus(OutboxStatus status);

    List<OutboxEvent> findByReservationId(Long reservationId);
}
```

- [ ] **Step 10: 스모크 테스트 작성 — `src/test/java/com/handys/reservation/repository/RepositorySmokeTest.java`**

```java
package com.handys.reservation.repository;

import com.handys.reservation.domain.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RepositorySmokeTest {

    @Autowired RoomInventoryRepository roomInventoryRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired OutboxEventRepository outboxEventRepository;

    @AfterEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
        reservationRepository.deleteAll();
        roomInventoryRepository.deleteAll();
    }

    @Test
    void 세_엔티티_모두_저장하고_조회할_수_있다() {
        String roomTypeId = "SMOKE_" + UUID.randomUUID();
        LocalDate stayDate = LocalDate.of(2030, 1, 1);

        RoomInventory inventory = roomInventoryRepository.save(new RoomInventory(roomTypeId, stayDate, 3));
        assertThat(roomInventoryRepository.findForUpdate(roomTypeId, stayDate)).isPresent();

        Reservation reservation = reservationRepository.save(
                new Reservation("AIRBNB", "SMOKE-EXT-" + UUID.randomUUID(), roomTypeId, stayDate, false));
        assertThat(reservationRepository.findByChannelAndExternalReservationId(
                reservation.getChannel(), reservation.getExternalReservationId())).isPresent();

        OutboxEvent event = outboxEventRepository.save(
                new OutboxEvent(reservation.getId(), OutboxEventType.DOOR_LOCK_ISSUE));
        assertThat(outboxEventRepository.findByReservationId(reservation.getId())).containsExactly(event);

        assertThat(inventory.getId()).isNotNull();
    }
}
```

- [ ] **Step 11: 테스트 실행 (docker-compose Postgres 기동 상태에서)**

Run: `docker-compose up -d && ./gradlew test --tests "com.handys.reservation.repository.RepositorySmokeTest"`
Expected: `BUILD SUCCESSFUL`, 1개 테스트 통과

- [ ] **Step 12: Commit**

```bash
git add src/main/java/com/handys/reservation/domain src/main/java/com/handys/reservation/repository \
  src/test/java/com/handys/reservation/repository
git commit -m "$(printf '도메인 엔티티와 리포지토리 추가\n\nRoomInventory/Reservation/OutboxEvent 엔티티와 비관적 락 조회 쿼리를 구현한다.')"
```

---

### Task 3: ReservationService 핵심 로직 (TDD)

**Files:**
- Create: `src/main/java/com/handys/reservation/service/ReservationCommand.java`
- Create: `src/main/java/com/handys/reservation/service/ReservationOutcome.java`
- Create: `src/main/java/com/handys/reservation/service/ReservationResult.java`
- Create: `src/main/java/com/handys/reservation/exception/RoomNotFoundException.java`
- Create: `src/main/java/com/handys/reservation/exception/SoldOutException.java`
- Create: `src/main/java/com/handys/reservation/exception/DuplicateReservationConflictException.java`
- Create: `src/main/java/com/handys/reservation/service/ReservationService.java`
- Test: `src/test/java/com/handys/reservation/service/ReservationServiceTest.java`

**Interfaces:**
- Consumes: Task 2의 `RoomInventoryRepository`, `ReservationRepository`, `OutboxEventRepository`, 엔티티들
- Produces:
  - `ReservationCommand(String channel, String externalReservationId, String roomTypeId, LocalDate stayDate, boolean simulateDownstreamFailure)`
  - `ReservationResult(Long reservationId, ReservationOutcome outcome)`, `ReservationOutcome { CONFIRMED, ALREADY_PROCESSED }`
  - `ReservationService.reserve(ReservationCommand): ReservationResult` — `RoomNotFoundException`/`SoldOutException`/`DuplicateReservationConflictException` throw

- [ ] **Step 1: 커맨드/결과 타입 작성 — `src/main/java/com/handys/reservation/service/ReservationCommand.java`**

```java
package com.handys.reservation.service;

import java.time.LocalDate;

public record ReservationCommand(
        String channel,
        String externalReservationId,
        String roomTypeId,
        LocalDate stayDate,
        boolean simulateDownstreamFailure
) {
}
```

- [ ] **Step 2: `src/main/java/com/handys/reservation/service/ReservationOutcome.java`**

```java
package com.handys.reservation.service;

public enum ReservationOutcome {
    CONFIRMED,
    ALREADY_PROCESSED
}
```

- [ ] **Step 3: `src/main/java/com/handys/reservation/service/ReservationResult.java`**

```java
package com.handys.reservation.service;

public record ReservationResult(Long reservationId, ReservationOutcome outcome) {
}
```

- [ ] **Step 4: 예외 타입 작성 — `src/main/java/com/handys/reservation/exception/RoomNotFoundException.java`**

```java
package com.handys.reservation.exception;

import java.time.LocalDate;

public class RoomNotFoundException extends RuntimeException {
    public RoomNotFoundException(String roomTypeId, LocalDate stayDate) {
        super("room inventory not found: roomTypeId=" + roomTypeId + ", stayDate=" + stayDate);
    }
}
```

- [ ] **Step 5: `src/main/java/com/handys/reservation/exception/SoldOutException.java`**

```java
package com.handys.reservation.exception;

import java.time.LocalDate;

public class SoldOutException extends RuntimeException {
    public SoldOutException(String roomTypeId, LocalDate stayDate) {
        super("no stock left: roomTypeId=" + roomTypeId + ", stayDate=" + stayDate);
    }
}
```

- [ ] **Step 6: `src/main/java/com/handys/reservation/exception/DuplicateReservationConflictException.java`**

```java
package com.handys.reservation.exception;

public class DuplicateReservationConflictException extends RuntimeException {
    public DuplicateReservationConflictException(String channel, String externalReservationId) {
        super("duplicate concurrent reservation: channel=" + channel
                + ", externalReservationId=" + externalReservationId);
    }
}
```

- [ ] **Step 7: 실패하는 단위 테스트 작성 — `src/test/java/com/handys/reservation/service/ReservationServiceTest.java`**

```java
package com.handys.reservation.service;

import com.handys.reservation.domain.OutboxEvent;
import com.handys.reservation.domain.Reservation;
import com.handys.reservation.domain.RoomInventory;
import com.handys.reservation.exception.DuplicateReservationConflictException;
import com.handys.reservation.exception.RoomNotFoundException;
import com.handys.reservation.exception.SoldOutException;
import com.handys.reservation.repository.OutboxEventRepository;
import com.handys.reservation.repository.ReservationRepository;
import com.handys.reservation.repository.RoomInventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReservationServiceTest {

    @Mock RoomInventoryRepository roomInventoryRepository;
    @Mock ReservationRepository reservationRepository;
    @Mock OutboxEventRepository outboxEventRepository;

    ReservationService reservationService;

    static final String CHANNEL = "AIRBNB";
    static final String EXTERNAL_ID = "AB-1";
    static final String ROOM_TYPE = "DELUXE_A";
    static final LocalDate STAY_DATE = LocalDate.of(2026, 8, 10);

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        reservationService = new ReservationService(roomInventoryRepository, reservationRepository, outboxEventRepository);
    }

    private ReservationCommand command() {
        return new ReservationCommand(CHANNEL, EXTERNAL_ID, ROOM_TYPE, STAY_DATE, false);
    }

    private void setId(Reservation reservation, Long id) {
        try {
            var field = Reservation.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(reservation, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void 재고행이_없으면_RoomNotFoundException() {
        when(roomInventoryRepository.findForUpdate(ROOM_TYPE, STAY_DATE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.reserve(command()))
                .isInstanceOf(RoomNotFoundException.class);
    }

    @Test
    void 이미_처리된_웹훅이면_ALREADY_PROCESSED를_반환한다() {
        RoomInventory inventory = new RoomInventory(ROOM_TYPE, STAY_DATE, 1);
        Reservation existing = new Reservation(CHANNEL, EXTERNAL_ID, ROOM_TYPE, STAY_DATE, false);
        setId(existing, 10L);

        when(roomInventoryRepository.findForUpdate(ROOM_TYPE, STAY_DATE)).thenReturn(Optional.of(inventory));
        when(reservationRepository.findByChannelAndExternalReservationId(CHANNEL, EXTERNAL_ID))
                .thenReturn(Optional.of(existing));

        ReservationResult result = reservationService.reserve(command());

        assertThat(result.outcome()).isEqualTo(ReservationOutcome.ALREADY_PROCESSED);
        assertThat(result.reservationId()).isEqualTo(10L);
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    void 재고가_없으면_SoldOutException() {
        RoomInventory inventory = new RoomInventory(ROOM_TYPE, STAY_DATE, 0);

        when(roomInventoryRepository.findForUpdate(ROOM_TYPE, STAY_DATE)).thenReturn(Optional.of(inventory));
        when(reservationRepository.findByChannelAndExternalReservationId(CHANNEL, EXTERNAL_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.reserve(command()))
                .isInstanceOf(SoldOutException.class);
    }

    @Test
    void 정상_예약이면_재고를_차감하고_outbox_2건을_생성한다() {
        RoomInventory inventory = new RoomInventory(ROOM_TYPE, STAY_DATE, 1);
        Reservation saved = new Reservation(CHANNEL, EXTERNAL_ID, ROOM_TYPE, STAY_DATE, false);
        setId(saved, 20L);

        when(roomInventoryRepository.findForUpdate(ROOM_TYPE, STAY_DATE)).thenReturn(Optional.of(inventory));
        when(reservationRepository.findByChannelAndExternalReservationId(CHANNEL, EXTERNAL_ID))
                .thenReturn(Optional.empty());
        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenReturn(saved);

        ReservationResult result = reservationService.reserve(command());

        assertThat(result.outcome()).isEqualTo(ReservationOutcome.CONFIRMED);
        assertThat(result.reservationId()).isEqualTo(20L);
        assertThat(inventory.getAvailableStock()).isEqualTo(0);
        verify(outboxEventRepository, times(2)).save(any(OutboxEvent.class));
    }

    @Test
    void insert시_유니크_제약_위반이면_DuplicateReservationConflictException() {
        RoomInventory inventory = new RoomInventory(ROOM_TYPE, STAY_DATE, 1);

        when(roomInventoryRepository.findForUpdate(ROOM_TYPE, STAY_DATE)).thenReturn(Optional.of(inventory));
        when(reservationRepository.findByChannelAndExternalReservationId(CHANNEL, EXTERNAL_ID))
                .thenReturn(Optional.empty());
        when(reservationRepository.saveAndFlush(any(Reservation.class)))
                .thenThrow(new DataIntegrityViolationException("unique violation"));

        assertThatThrownBy(() -> reservationService.reserve(command()))
                .isInstanceOf(DuplicateReservationConflictException.class);
    }
}
```

- [ ] **Step 8: 테스트 실행 → 컴파일 실패 확인 (ReservationService 없음)**

Run: `./gradlew test --tests "com.handys.reservation.service.ReservationServiceTest"`
Expected: FAIL — `cannot find symbol: class ReservationService`

- [ ] **Step 9: `ReservationService` 구현 — `src/main/java/com/handys/reservation/service/ReservationService.java`**

```java
package com.handys.reservation.service;

import com.handys.reservation.domain.OutboxEvent;
import com.handys.reservation.domain.OutboxEventType;
import com.handys.reservation.domain.Reservation;
import com.handys.reservation.domain.RoomInventory;
import com.handys.reservation.exception.DuplicateReservationConflictException;
import com.handys.reservation.exception.RoomNotFoundException;
import com.handys.reservation.exception.SoldOutException;
import com.handys.reservation.repository.OutboxEventRepository;
import com.handys.reservation.repository.ReservationRepository;
import com.handys.reservation.repository.RoomInventoryRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class ReservationService {

    private final RoomInventoryRepository roomInventoryRepository;
    private final ReservationRepository reservationRepository;
    private final OutboxEventRepository outboxEventRepository;

    public ReservationService(RoomInventoryRepository roomInventoryRepository,
                               ReservationRepository reservationRepository,
                               OutboxEventRepository outboxEventRepository) {
        this.roomInventoryRepository = roomInventoryRepository;
        this.reservationRepository = reservationRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional
    public ReservationResult reserve(ReservationCommand command) {
        RoomInventory inventory = roomInventoryRepository
                .findForUpdate(command.roomTypeId(), command.stayDate())
                .orElseThrow(() -> new RoomNotFoundException(command.roomTypeId(), command.stayDate()));

        Optional<Reservation> existing = reservationRepository.findByChannelAndExternalReservationId(
                command.channel(), command.externalReservationId());
        if (existing.isPresent()) {
            return new ReservationResult(existing.get().getId(), ReservationOutcome.ALREADY_PROCESSED);
        }

        if (!inventory.hasStock()) {
            throw new SoldOutException(command.roomTypeId(), command.stayDate());
        }
        inventory.decrease();

        Reservation reservation;
        try {
            reservation = reservationRepository.saveAndFlush(
                    new Reservation(command.channel(), command.externalReservationId(),
                            command.roomTypeId(), command.stayDate(), command.simulateDownstreamFailure()));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateReservationConflictException(command.channel(), command.externalReservationId());
        }

        outboxEventRepository.save(new OutboxEvent(reservation.getId(), OutboxEventType.DOOR_LOCK_ISSUE));
        outboxEventRepository.save(new OutboxEvent(reservation.getId(), OutboxEventType.SETTLEMENT_TRIGGER));

        return new ReservationResult(reservation.getId(), ReservationOutcome.CONFIRMED);
    }
}
```

- [ ] **Step 10: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.handys.reservation.service.ReservationServiceTest"`
Expected: `BUILD SUCCESSFUL`, 5개 테스트 통과

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/handys/reservation/service src/main/java/com/handys/reservation/exception \
  src/test/java/com/handys/reservation/service
git commit -m "$(printf 'ReservationService 핵심 로직 구현\n\n비관적 락, 멱등성 체크, 재고 차감, outbox 이벤트 발행을 한 트랜잭션으로 처리한다.')"
```

---

### Task 4: REST API (예약 웹훅, 재고 시딩, 에러 처리)

**Files:**
- Create: `src/main/java/com/handys/reservation/web/dto/ReserveRequest.java`
- Create: `src/main/java/com/handys/reservation/web/dto/ReserveResponse.java`
- Create: `src/main/java/com/handys/reservation/web/dto/ReservationDetailResponse.java`
- Create: `src/main/java/com/handys/reservation/web/dto/ErrorResponse.java`
- Create: `src/main/java/com/handys/reservation/web/dto/CreateRoomInventoryRequest.java`
- Create: `src/main/java/com/handys/reservation/web/dto/RoomInventoryResponse.java`
- Create: `src/main/java/com/handys/reservation/web/ReservationController.java`
- Create: `src/main/java/com/handys/reservation/web/RoomInventoryController.java`
- Create: `src/main/java/com/handys/reservation/web/GlobalExceptionHandler.java`
- Test: `src/test/java/com/handys/reservation/web/ReservationControllerTest.java`

**Interfaces:**
- Consumes: Task 3의 `ReservationService.reserve(ReservationCommand): ReservationResult`, `ReservationRepository`, Task 2의 `RoomInventoryRepository`
- Produces: `POST /api/reservations`, `GET /api/reservations/{id}`, `POST /api/room-inventories` 엔드포인트, 공통 에러 포맷 `{errorCode, message}`

- [ ] **Step 1: DTO 작성 — `src/main/java/com/handys/reservation/web/dto/ReserveRequest.java`**

```java
package com.handys.reservation.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record ReserveRequest(
        @NotBlank String channel,
        @NotBlank String externalReservationId,
        @NotBlank String roomTypeId,
        @NotNull LocalDate stayDate,
        boolean simulateDownstreamFailure
) {
}
```

- [ ] **Step 2: `src/main/java/com/handys/reservation/web/dto/ReserveResponse.java`**

```java
package com.handys.reservation.web.dto;

public record ReserveResponse(Long reservationId, String status) {
}
```

- [ ] **Step 3: `src/main/java/com/handys/reservation/web/dto/ReservationDetailResponse.java`**

```java
package com.handys.reservation.web.dto;

import java.time.LocalDate;

public record ReservationDetailResponse(
        Long id, String channel, String externalReservationId,
        String roomTypeId, LocalDate stayDate, String status
) {
}
```

- [ ] **Step 4: `src/main/java/com/handys/reservation/web/dto/ErrorResponse.java`**

```java
package com.handys.reservation.web.dto;

public record ErrorResponse(String errorCode, String message) {
}
```

- [ ] **Step 5: `src/main/java/com/handys/reservation/web/dto/CreateRoomInventoryRequest.java`**

```java
package com.handys.reservation.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record CreateRoomInventoryRequest(
        @NotBlank String roomTypeId,
        @NotNull LocalDate stayDate,
        @Min(0) int totalStock
) {
}
```

- [ ] **Step 6: `src/main/java/com/handys/reservation/web/dto/RoomInventoryResponse.java`**

```java
package com.handys.reservation.web.dto;

import java.time.LocalDate;

public record RoomInventoryResponse(
        Long id, String roomTypeId, LocalDate stayDate, int totalStock, int availableStock
) {
}
```

- [ ] **Step 7: 실패하는 통합 테스트 작성 — `src/test/java/com/handys/reservation/web/ReservationControllerTest.java`**

```java
package com.handys.reservation.web;

import com.handys.reservation.domain.RoomInventory;
import com.handys.reservation.repository.OutboxEventRepository;
import com.handys.reservation.repository.ReservationRepository;
import com.handys.reservation.repository.RoomInventoryRepository;
import com.handys.reservation.web.dto.ErrorResponse;
import com.handys.reservation.web.dto.ReserveRequest;
import com.handys.reservation.web.dto.ReserveResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReservationControllerTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired RoomInventoryRepository roomInventoryRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired OutboxEventRepository outboxEventRepository;

    LocalDate stayDate = LocalDate.of(2030, 1, 1);

    @AfterEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
        reservationRepository.deleteAll();
        roomInventoryRepository.deleteAll();
    }

    private String seedInventory(int stock) {
        String roomTypeId = "TEST_" + UUID.randomUUID();
        roomInventoryRepository.save(new RoomInventory(roomTypeId, stayDate, stock));
        return roomTypeId;
    }

    @Test
    void 신규_예약은_201과_CONFIRMED를_반환한다() {
        String roomTypeId = seedInventory(1);
        ReserveRequest request = new ReserveRequest("AIRBNB", "EXT-" + UUID.randomUUID(), roomTypeId, stayDate, false);

        ResponseEntity<ReserveResponse> response = restTemplate.postForEntity("/api/reservations", request, ReserveResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().status()).isEqualTo("CONFIRMED");
    }

    @Test
    void 같은_웹훅을_다시_보내면_200과_ALREADY_PROCESSED를_반환한다() {
        String roomTypeId = seedInventory(2);
        String externalId = "EXT-" + UUID.randomUUID();
        ReserveRequest request = new ReserveRequest("AIRBNB", externalId, roomTypeId, stayDate, false);
        restTemplate.postForEntity("/api/reservations", request, ReserveResponse.class);

        ResponseEntity<ReserveResponse> response = restTemplate.postForEntity("/api/reservations", request, ReserveResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("ALREADY_PROCESSED");
    }

    @Test
    void 재고가_없으면_409_SOLD_OUT을_반환한다() {
        String roomTypeId = seedInventory(0);
        ReserveRequest request = new ReserveRequest("AIRBNB", "EXT-" + UUID.randomUUID(), roomTypeId, stayDate, false);

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity("/api/reservations", request, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().errorCode()).isEqualTo("SOLD_OUT");
    }

    @Test
    void 존재하지_않는_객실타입이면_404_ROOM_NOT_FOUND를_반환한다() {
        ReserveRequest request = new ReserveRequest("AIRBNB", "EXT-" + UUID.randomUUID(), "NO_SUCH_ROOM", stayDate, false);

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity("/api/reservations", request, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().errorCode()).isEqualTo("ROOM_NOT_FOUND");
    }
}
```

- [ ] **Step 8: 테스트 실행 → 실패 확인 (컨트롤러 없음, 404 for all)**

Run: `docker-compose up -d && ./gradlew test --tests "com.handys.reservation.web.ReservationControllerTest"`
Expected: FAIL — 모든 요청이 404(엔드포인트 없음)로 응답해 assertion 실패

- [ ] **Step 9: `GlobalExceptionHandler` 작성 — `src/main/java/com/handys/reservation/web/GlobalExceptionHandler.java`**

```java
package com.handys.reservation.web;

import com.handys.reservation.exception.DuplicateReservationConflictException;
import com.handys.reservation.exception.RoomNotFoundException;
import com.handys.reservation.exception.SoldOutException;
import com.handys.reservation.web.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RoomNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRoomNotFound(RoomNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("ROOM_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(SoldOutException.class)
    public ResponseEntity<ErrorResponse> handleSoldOut(SoldOutException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("SOLD_OUT", e.getMessage()));
    }

    @ExceptionHandler(DuplicateReservationConflictException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateConflict(DuplicateReservationConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("DUPLICATE_CONFLICT", e.getMessage()));
    }
}
```

- [ ] **Step 10: `ReservationController` 작성 — `src/main/java/com/handys/reservation/web/ReservationController.java`**

```java
package com.handys.reservation.web;

import com.handys.reservation.repository.ReservationRepository;
import com.handys.reservation.service.ReservationCommand;
import com.handys.reservation.service.ReservationOutcome;
import com.handys.reservation.service.ReservationResult;
import com.handys.reservation.service.ReservationService;
import com.handys.reservation.web.dto.ReservationDetailResponse;
import com.handys.reservation.web.dto.ReserveRequest;
import com.handys.reservation.web.dto.ReserveResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationService reservationService;
    private final ReservationRepository reservationRepository;

    public ReservationController(ReservationService reservationService,
                                  ReservationRepository reservationRepository) {
        this.reservationService = reservationService;
        this.reservationRepository = reservationRepository;
    }

    @PostMapping
    public ResponseEntity<ReserveResponse> reserve(@Valid @RequestBody ReserveRequest request) {
        ReservationCommand command = new ReservationCommand(
                request.channel(), request.externalReservationId(), request.roomTypeId(),
                request.stayDate(), request.simulateDownstreamFailure());
        ReservationResult result = reservationService.reserve(command);
        HttpStatus status = result.outcome() == ReservationOutcome.CONFIRMED ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(new ReserveResponse(result.reservationId(), result.outcome().name()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReservationDetailResponse> findById(@PathVariable Long id) {
        return reservationRepository.findById(id)
                .map(r -> ResponseEntity.ok(new ReservationDetailResponse(
                        r.getId(), r.getChannel(), r.getExternalReservationId(),
                        r.getRoomTypeId(), r.getStayDate(), r.getStatus().name())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
```

- [ ] **Step 11: `RoomInventoryController` 작성 — `src/main/java/com/handys/reservation/web/RoomInventoryController.java`**

```java
package com.handys.reservation.web;

import com.handys.reservation.domain.RoomInventory;
import com.handys.reservation.repository.RoomInventoryRepository;
import com.handys.reservation.web.dto.CreateRoomInventoryRequest;
import com.handys.reservation.web.dto.RoomInventoryResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/room-inventories")
public class RoomInventoryController {

    private final RoomInventoryRepository roomInventoryRepository;

    public RoomInventoryController(RoomInventoryRepository roomInventoryRepository) {
        this.roomInventoryRepository = roomInventoryRepository;
    }

    @PostMapping
    public ResponseEntity<RoomInventoryResponse> create(@Valid @RequestBody CreateRoomInventoryRequest request) {
        RoomInventory saved = roomInventoryRepository.save(
                new RoomInventory(request.roomTypeId(), request.stayDate(), request.totalStock()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new RoomInventoryResponse(
                saved.getId(), saved.getRoomTypeId(), saved.getStayDate(),
                saved.getTotalStock(), saved.getAvailableStock()));
    }
}
```

- [ ] **Step 12: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.handys.reservation.web.ReservationControllerTest"`
Expected: `BUILD SUCCESSFUL`, 4개 테스트 통과

- [ ] **Step 13: Commit**

```bash
git add src/main/java/com/handys/reservation/web src/test/java/com/handys/reservation/web
git commit -m "$(printf 'REST API 구현: 예약 웹훅/재고 시딩/공통 에러 응답\n\nPOST /api/reservations, GET /api/reservations/{id}, POST /api/room-inventories를 추가한다.')"
```

---

### Task 5: 동시성 재현 테스트 — 마지막 재고 경쟁

**Files:**
- Test: `src/test/java/com/handys/reservation/web/ReservationConcurrencyTest.java`

**Interfaces:**
- Consumes: Task 4의 `POST /api/reservations` 엔드포인트, Task 2의 리포지토리

이 태스크는 기존 구현을 검증하는 순수 테스트 태스크다 (구현 코드 변경 없음). "실패하는 테스트 → 구현" 사이클 대신, 테스트가 처음부터 통과해야 하며 통과하지 않으면 Task 3/4의 구현 버그를 의미한다.

- [ ] **Step 1: 동시성 테스트 작성 — `src/test/java/com/handys/reservation/web/ReservationConcurrencyTest.java`**

```java
package com.handys.reservation.web;

import com.handys.reservation.domain.RoomInventory;
import com.handys.reservation.repository.OutboxEventRepository;
import com.handys.reservation.repository.ReservationRepository;
import com.handys.reservation.repository.RoomInventoryRepository;
import com.handys.reservation.web.dto.ReserveRequest;
import com.handys.reservation.web.dto.ReserveResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReservationConcurrencyTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired RoomInventoryRepository roomInventoryRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired OutboxEventRepository outboxEventRepository;

    @AfterEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
        reservationRepository.deleteAll();
        roomInventoryRepository.deleteAll();
    }

    @Test
    void 마지막_재고_하나를_두고_50개가_동시에_경쟁해도_정확히_하나만_성공한다() throws Exception {
        String roomTypeId = "RACE_" + UUID.randomUUID();
        LocalDate stayDate = LocalDate.of(2030, 1, 1);
        roomInventoryRepository.save(new RoomInventory(roomTypeId, stayDate, 1));

        int requestCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch readyLatch = new CountDownLatch(requestCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger soldOutCount = new AtomicInteger();

        List<Callable<Void>> tasks = IntStream.range(0, requestCount)
                .<Callable<Void>>mapToObj(i -> () -> {
                    readyLatch.countDown();
                    startLatch.await();
                    ReserveRequest request = new ReserveRequest(
                            "AIRBNB", "EXT-" + UUID.randomUUID(), roomTypeId, stayDate, false);
                    ResponseEntity<ReserveResponse> response =
                            restTemplate.postForEntity("/api/reservations", request, ReserveResponse.class);
                    if (response.getStatusCode() == HttpStatus.CREATED) {
                        successCount.incrementAndGet();
                    } else if (response.getStatusCode() == HttpStatus.CONFLICT) {
                        soldOutCount.incrementAndGet();
                    }
                    return null;
                })
                .toList();

        List<Future<Void>> futures = tasks.stream().map(executor::submit).toList();
        readyLatch.await();
        startLatch.countDown();
        for (Future<Void> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(soldOutCount.get()).isEqualTo(requestCount - 1);
    }
}
```

- [ ] **Step 2: 테스트 실행**

Run: `docker-compose up -d && ./gradlew test --tests "com.handys.reservation.web.ReservationConcurrencyTest"`
Expected: `BUILD SUCCESSFUL`. 실패한다면 `ReservationService.reserve()`의 락 순서(Task 3, Step 9)를 다시 확인한다 — 재고 조회가 `findForUpdate`(락 있음)가 아닌 일반 조회를 쓰고 있지는 않은지가 가장 흔한 원인이다.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/handys/reservation/web/ReservationConcurrencyTest.java
git commit -m "$(printf '동시성 재현 테스트 추가: 마지막 재고 경쟁\n\n재고 1개를 두고 50개 동시 요청을 보내 정확히 하나만 성공함을 검증한다.')"
```

---

### Task 6: 멱등성 재현 테스트 — 동시 중복 웹훅

**Files:**
- Test: `src/test/java/com/handys/reservation/web/ReservationIdempotencyConcurrencyTest.java`

**Interfaces:**
- Consumes: Task 4의 `POST /api/reservations`, Task 2의 리포지토리

- [ ] **Step 1: 테스트 작성 — `src/test/java/com/handys/reservation/web/ReservationIdempotencyConcurrencyTest.java`**

```java
package com.handys.reservation.web;

import com.handys.reservation.domain.RoomInventory;
import com.handys.reservation.repository.OutboxEventRepository;
import com.handys.reservation.repository.ReservationRepository;
import com.handys.reservation.repository.RoomInventoryRepository;
import com.handys.reservation.web.dto.ReserveRequest;
import com.handys.reservation.web.dto.ReserveResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReservationIdempotencyConcurrencyTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired RoomInventoryRepository roomInventoryRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired OutboxEventRepository outboxEventRepository;

    @AfterEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
        reservationRepository.deleteAll();
        roomInventoryRepository.deleteAll();
    }

    @Test
    void 같은_웹훅이_동시에_20번_재전송돼도_예약은_한_건만_반영된다() throws Exception {
        String roomTypeId = "DUP_" + UUID.randomUUID();
        String externalId = "DUP-EXT-" + UUID.randomUUID();
        LocalDate stayDate = LocalDate.of(2030, 1, 1);
        roomInventoryRepository.save(new RoomInventory(roomTypeId, stayDate, 10));

        int requestCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch readyLatch = new CountDownLatch(requestCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger createdCount = new AtomicInteger();

        List<Callable<Void>> tasks = IntStream.range(0, requestCount)
                .<Callable<Void>>mapToObj(i -> () -> {
                    readyLatch.countDown();
                    startLatch.await();
                    ReserveRequest request = new ReserveRequest("AIRBNB", externalId, roomTypeId, stayDate, false);
                    ResponseEntity<ReserveResponse> response =
                            restTemplate.postForEntity("/api/reservations", request, ReserveResponse.class);
                    if (response.getStatusCode() == HttpStatus.CREATED) {
                        createdCount.incrementAndGet();
                    }
                    return null;
                })
                .toList();

        List<Future<Void>> futures = tasks.stream().map(executor::submit).toList();
        readyLatch.await();
        startLatch.countDown();
        for (Future<Void> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(createdCount.get()).isEqualTo(1);
        assertThat(reservationRepository.findByChannelAndExternalReservationId("AIRBNB", externalId)).isPresent();
        assertThat(reservationRepository.count()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 테스트 실행**

Run: `docker-compose up -d && ./gradlew test --tests "com.handys.reservation.web.ReservationIdempotencyConcurrencyTest"`
Expected: `BUILD SUCCESSFUL`. 실패 시 Task 3 Step 9의 `DataIntegrityViolationException` catch 블록이 `saveAndFlush` 호출(즉시 flush로 즉시 제약 위반 감지)이 아닌 `save`로 되어 있지 않은지 확인한다.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/handys/reservation/web/ReservationIdempotencyConcurrencyTest.java
git commit -m "$(printf '멱등성 재현 테스트 추가: 동시 중복 웹훅\n\n같은 웹훅을 20개 동시 전송해도 예약이 한 건만 반영됨을 검증한다.')"
```

---

### Task 7: Outbox 재시도/최종 실패 처리

**Files:**
- Create: `src/main/java/com/handys/reservation/downstream/DownstreamClient.java`
- Create: `src/main/java/com/handys/reservation/downstream/DownstreamCallFailedException.java`
- Create: `src/main/java/com/handys/reservation/downstream/StubDownstreamClient.java`
- Create: `src/main/java/com/handys/reservation/service/OutboxDispatcher.java`
- Create: `src/main/java/com/handys/reservation/web/dto/OutboxEventResponse.java`
- Create: `src/main/java/com/handys/reservation/web/OutboxEventQueryController.java`
- Test: `src/test/java/com/handys/reservation/service/OutboxDispatcherTest.java`

**Interfaces:**
- Consumes: Task 2의 `OutboxEventRepository`, `ReservationRepository`, Task 3의 `ReservationService`
- Produces: `DownstreamClient.call(OutboxEventType, Long reservationId, boolean simulateFailure)`, `OutboxDispatcher.dispatchOne(Long outboxEventId)`, `OutboxDispatcher.dispatchPendingEvents()`(스케줄), `GET /api/outbox-events?status=`

- [ ] **Step 1: 다운스트림 클라이언트 인터페이스 — `src/main/java/com/handys/reservation/downstream/DownstreamClient.java`**

```java
package com.handys.reservation.downstream;

import com.handys.reservation.domain.OutboxEventType;

public interface DownstreamClient {
    void call(OutboxEventType eventType, Long reservationId, boolean simulateFailure);
}
```

- [ ] **Step 2: `src/main/java/com/handys/reservation/downstream/DownstreamCallFailedException.java`**

```java
package com.handys.reservation.downstream;

import com.handys.reservation.domain.OutboxEventType;

public class DownstreamCallFailedException extends RuntimeException {
    public DownstreamCallFailedException(OutboxEventType eventType, Long reservationId) {
        super("downstream call failed: eventType=" + eventType + ", reservationId=" + reservationId);
    }
}
```

- [ ] **Step 3: 스텁 구현 — `src/main/java/com/handys/reservation/downstream/StubDownstreamClient.java`**

```java
package com.handys.reservation.downstream;

import com.handys.reservation.domain.OutboxEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class StubDownstreamClient implements DownstreamClient {

    private static final Logger log = LoggerFactory.getLogger(StubDownstreamClient.class);

    @Override
    public void call(OutboxEventType eventType, Long reservationId, boolean simulateFailure) {
        if (simulateFailure) {
            throw new DownstreamCallFailedException(eventType, reservationId);
        }
        log.info("다운스트림 처리 완료: eventType={}, reservationId={}", eventType, reservationId);
    }
}
```

- [ ] **Step 4: 실패하는 테스트 작성 — `src/test/java/com/handys/reservation/service/OutboxDispatcherTest.java`**

```java
package com.handys.reservation.service;

import com.handys.reservation.domain.OutboxEvent;
import com.handys.reservation.domain.OutboxStatus;
import com.handys.reservation.domain.RoomInventory;
import com.handys.reservation.repository.OutboxEventRepository;
import com.handys.reservation.repository.ReservationRepository;
import com.handys.reservation.repository.RoomInventoryRepository;
import com.handys.reservation.web.dto.OutboxEventResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutboxDispatcherTest {

    @Autowired ReservationService reservationService;
    @Autowired OutboxDispatcher outboxDispatcher;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired RoomInventoryRepository roomInventoryRepository;
    @Autowired TestRestTemplate restTemplate;

    @AfterEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
        reservationRepository.deleteAll();
        roomInventoryRepository.deleteAll();
    }

    @Test
    void 다운스트림_호출이_계속_실패하면_최대_재시도_후_FAILED로_마감되고_조회_API로_추적된다() {
        String roomTypeId = "OUTBOX_" + UUID.randomUUID();
        LocalDate stayDate = LocalDate.of(2030, 1, 1);
        roomInventoryRepository.save(new RoomInventory(roomTypeId, stayDate, 1));

        ReservationCommand command = new ReservationCommand(
                "AIRBNB", "OUTBOX-EXT-" + UUID.randomUUID(), roomTypeId, stayDate, true);
        ReservationResult result = reservationService.reserve(command);

        List<OutboxEvent> events = outboxEventRepository.findByReservationId(result.reservationId());
        assertThat(events).hasSize(2);
        OutboxEvent target = events.get(0);

        for (int i = 0; i < target.getMaxRetry(); i++) {
            outboxDispatcher.dispatchOne(target.getId());
        }

        OutboxEvent updated = outboxEventRepository.findById(target.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(updated.getRetryCount()).isEqualTo(target.getMaxRetry());

        ResponseEntity<OutboxEventResponse[]> response =
                restTemplate.getForEntity("/api/outbox-events?status=FAILED", OutboxEventResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .extracting(OutboxEventResponse::id)
                .contains(target.getId());
    }
}
```

- [ ] **Step 5: 테스트 실행 → 컴파일 실패 확인 (OutboxDispatcher 없음)**

Run: `docker-compose up -d && ./gradlew test --tests "com.handys.reservation.service.OutboxDispatcherTest"`
Expected: FAIL — `cannot find symbol: class OutboxDispatcher`

- [ ] **Step 6: `OutboxDispatcher` 구현 — `src/main/java/com/handys/reservation/service/OutboxDispatcher.java`**

```java
package com.handys.reservation.service;

import com.handys.reservation.domain.OutboxEvent;
import com.handys.reservation.domain.OutboxStatus;
import com.handys.reservation.domain.Reservation;
import com.handys.reservation.downstream.DownstreamClient;
import com.handys.reservation.repository.OutboxEventRepository;
import com.handys.reservation.repository.ReservationRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class OutboxDispatcher {

    private final OutboxEventRepository outboxEventRepository;
    private final ReservationRepository reservationRepository;
    private final DownstreamClient downstreamClient;

    public OutboxDispatcher(OutboxEventRepository outboxEventRepository,
                             ReservationRepository reservationRepository,
                             DownstreamClient downstreamClient) {
        this.outboxEventRepository = outboxEventRepository;
        this.reservationRepository = reservationRepository;
        this.downstreamClient = downstreamClient;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-fixed-delay-ms:3000}")
    public void dispatchPendingEvents() {
        List<OutboxEvent> pending = outboxEventRepository
                .findByStatusAndNextRetryAtLessThanEqual(OutboxStatus.PENDING, LocalDateTime.now());
        for (OutboxEvent event : pending) {
            dispatchOne(event.getId());
        }
    }

    @Transactional
    public void dispatchOne(Long outboxEventId) {
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
}
```

- [ ] **Step 7: 조회 DTO/컨트롤러 작성 — `src/main/java/com/handys/reservation/web/dto/OutboxEventResponse.java`**

```java
package com.handys.reservation.web.dto;

public record OutboxEventResponse(
        Long id, Long reservationId, String eventType, String status, int retryCount, String lastError
) {
}
```

- [ ] **Step 8: `src/main/java/com/handys/reservation/web/OutboxEventQueryController.java`**

```java
package com.handys.reservation.web;

import com.handys.reservation.domain.OutboxStatus;
import com.handys.reservation.repository.OutboxEventRepository;
import com.handys.reservation.web.dto.OutboxEventResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class OutboxEventQueryController {

    private final OutboxEventRepository outboxEventRepository;

    public OutboxEventQueryController(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @GetMapping("/api/outbox-events")
    public List<OutboxEventResponse> findByStatus(@RequestParam OutboxStatus status) {
        return outboxEventRepository.findByStatus(status).stream()
                .map(e -> new OutboxEventResponse(e.getId(), e.getReservationId(), e.getEventType().name(),
                        e.getStatus().name(), e.getRetryCount(), e.getLastError()))
                .toList();
    }
}
```

- [ ] **Step 9: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.handys.reservation.service.OutboxDispatcherTest"`
Expected: `BUILD SUCCESSFUL`, 1개 테스트 통과

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/handys/reservation/downstream src/main/java/com/handys/reservation/service/OutboxDispatcher.java \
  src/main/java/com/handys/reservation/web/dto/OutboxEventResponse.java \
  src/main/java/com/handys/reservation/web/OutboxEventQueryController.java \
  src/test/java/com/handys/reservation/service/OutboxDispatcherTest.java
git commit -m "$(printf 'Outbox 재시도/최종 실패 처리 구현\n\n스케줄러 폴링으로 다운스트림 스텁을 호출하고, 재시도 소진 시 FAILED로 마감해 추적 API로 노출한다.')"
```

---

### Task 8: 전체 테스트 확인 및 README 작성

**Files:**
- Create: `README.md`

**Interfaces:**
- Consumes: Task 1~7의 전체 결과물

- [ ] **Step 1: 전체 테스트 스위트 실행**

Run: `docker-compose up -d && ./gradlew test`
Expected: `BUILD SUCCESSFUL`, 모든 테스트(단위 + 통합 + 동시성) 통과

- [ ] **Step 2: `README.md` 작성**

```markdown
# reservation-integrity-service

여러 OTA 채널에서 동시에 들어오는 예약 웹훅을 오버부킹 없이, 중복 없이 안전하게 반영하는 백엔드 서비스.

문제 정의는 [PROBLEM.md](./PROBLEM.md), 설계는 [DESIGN.md](./DESIGN.md) 참고.

## 실행 방법

\`\`\`bash
docker-compose up -d   # 로컬 PostgreSQL 기동
./gradlew bootRun      # http://localhost:8080
\`\`\`

## 테스트 실행

동시성/멱등성 통합 테스트는 실제 PostgreSQL이 필요하므로, 반드시 `docker-compose up -d`를 먼저 실행해야 합니다.

\`\`\`bash
docker-compose up -d
./gradlew test
\`\`\`

## API 사용 예시

\`\`\`bash
# 예약 확정 웹훅 시뮬레이션 (V2__seed_data.sql에 DELUXE_A/2026-08-10 재고 5개가 미리 들어있음)
curl -X POST http://localhost:8080/api/reservations \\
  -H "Content-Type: application/json" \\
  -d '{"channel":"AIRBNB","externalReservationId":"AB-1","roomTypeId":"DELUXE_A","stayDate":"2026-08-10"}'

# 같은 웹훅 재전송 → 200 ALREADY_PROCESSED
curl -X POST http://localhost:8080/api/reservations \\
  -H "Content-Type: application/json" \\
  -d '{"channel":"AIRBNB","externalReservationId":"AB-1","roomTypeId":"DELUXE_A","stayDate":"2026-08-10"}'

# 후속 처리 강제 실패 시뮬레이션 → outbox 재시도 후 FAILED로 추적됨
curl -X POST http://localhost:8080/api/reservations \\
  -H "Content-Type: application/json" \\
  -d '{"channel":"AIRBNB","externalReservationId":"AB-2","roomTypeId":"DELUXE_A","stayDate":"2026-08-10","simulateDownstreamFailure":true}'

# 최종 실패한 후속 처리 추적
curl http://localhost:8080/api/outbox-events?status=FAILED
\`\`\`

## AI 활용

이 프로젝트의 설계 논의와 AI 협업 과정은 [DESIGN.md](./DESIGN.md#ai-협업-과정)에 정리되어 있습니다.
```

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "$(printf 'README 작성: 실행 방법과 API 사용 예시 정리\n\n실행/테스트 방법, curl 예시, 설계 문서 링크를 추가한다.')"
```

## Self-Review 결과

- **스펙 커버리지**: DESIGN.md의 API 명세(4개 엔드포인트), 동시성/멱등성 처리 순서, Outbox 재시도, 테스트 전략(단위/동시성/멱등성/재시도) 모두 Task 1~8에서 다룬다.
- **플레이스홀더**: 없음 — 모든 코드가 완전한 형태로 작성됨.
- **타입 일관성**: `ReservationCommand`/`ReservationResult`/`ReservationOutcome`(Task 3)이 `ReservationController`(Task 4)와 테스트들에서 동일한 시그니처로 사용됨. `RoomInventoryRepository.findForUpdate`, `ReservationRepository.findByChannelAndExternalReservationId`, `OutboxEventRepository.findByStatus`/`findByStatusAndNextRetryAtLessThanEqual`/`findByReservationId` 모두 Task 2에서 정의된 그대로 이후 태스크에서 사용됨.

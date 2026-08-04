# reservation-integrity-service

여러 OTA 채널에서 동시에 들어오는 예약 웹훅을 오버부킹 없이, 중복 없이 안전하게 반영하는 백엔드 서비스.

문제 정의는 [PROBLEM.md](./PROBLEM.md), 설계는 [DESIGN.md](./DESIGN.md) 참고.

## 실행 방법

```bash
docker-compose up -d   # 로컬 PostgreSQL 기동
./gradlew bootRun      # http://localhost:8080
```

## 테스트 실행

동시성/멱등성 통합 테스트는 실제 PostgreSQL이 필요하므로, 반드시 `docker-compose up -d`를 먼저 실행해야 합니다.

```bash
docker-compose up -d
./gradlew test
```

## API 사용 예시

```bash
# 예약 확정 웹훅 시뮬레이션 (V2__seed_data.sql에 DELUXE_A/2026-08-10 재고 5개가 미리 들어있음)
curl -X POST http://localhost:8080/api/reservations \
  -H "Content-Type: application/json" \
  -d '{"channel":"AIRBNB","externalReservationId":"AB-1","roomTypeId":"DELUXE_A","stayDate":"2026-08-10"}'

# 같은 웹훅 재전송 → 200 ALREADY_PROCESSED
curl -X POST http://localhost:8080/api/reservations \
  -H "Content-Type: application/json" \
  -d '{"channel":"AIRBNB","externalReservationId":"AB-1","roomTypeId":"DELUXE_A","stayDate":"2026-08-10"}'

# 후속 처리 강제 실패 시뮬레이션 → outbox 재시도 후 FAILED로 추적됨
curl -X POST http://localhost:8080/api/reservations \
  -H "Content-Type: application/json" \
  -d '{"channel":"AIRBNB","externalReservationId":"AB-2","roomTypeId":"DELUXE_A","stayDate":"2026-08-10","simulateDownstreamFailure":true}'

# 최종 실패한 후속 처리 추적
curl http://localhost:8080/api/outbox-events?status=FAILED
```

## AI 활용

이 프로젝트의 설계 논의와 AI 협업 과정은 [DESIGN.md](./DESIGN.md#ai-협업-과정)에 정리되어 있습니다.

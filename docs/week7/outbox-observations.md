# W7 관찰 기록 — 주문 확정 Outbox·relay·누락 복구

설계: [design.md 7장](../week2/design.md), DR-46~51.
이 문서의 값은 2026-11-05, `volume-7` 작업 트리에서 아래 테스트를 실행해 로그(`[W7 관찰: ...]`, relay·consumer 로그)로 얻은 것이다.
실행 환경: Testcontainers 단일 broker `apache/kafka:3.8.1`(KRaft, replica 1), `mysql:8.0`, `redis:latest`, JVM `user.timezone=Asia/Seoul`. 카드 값·비밀정보는 기록하지 않는다.

```bash
# Docker 필요. Testcontainers 가 실행이 끝나면 컨테이너를 정리한다.
./gradlew :apps:commerce-api:test --tests '*OutboxAtomicityTest' --tests '*OrderEventRelayTest' --tests '*OrderConfirmedBackfillTest'
./gradlew :tests:commerce-e2e:test   # W6MissingEventRecoveryTest, OutboxDeliveryE2ETest (클래스마다 새 JVM·새 컨테이너)

# 회귀 (Checkstyle·ArchUnit 포함)
./gradlew :apps:commerce-api:check :apps:commerce-streamer:check :tests:commerce-e2e:check
```

회귀 결과: commerce-api 486건, commerce-streamer 32건, commerce-e2e 2건. 실패·skip 0.

ID·시각은 실행마다 새로 생긴 값이다. e2e 는 클래스마다 새 DB 라 orderId 가 1 부터다.

## 1. 중단 위치별로 남아야 하는 것 — 예측과 관찰

먼저 예측을 적고, 테스트에서 실제 DB·broker·구매 활동을 새로 조회해 비교했다. 일치하지 않은 칸은 없었다.

| 중단 위치 | 예측: DB | 예측: broker | 예측: 구매 활동 | 관찰 (테스트) |
|---|---|---|---|---|
| Outbox insert 전 (직렬화 실패) | 주문 AWAITING_PAYMENT, 원장 ADJUSTMENT·RESERVE 만, Outbox 0 | 변화 없음 | 없음 | 같음. PG create 1 그대로, 같은 거래 재조회로 CONFIRMED·Outbox 1 (`OutboxAtomicityTest`) |
| Outbox insert 뒤 commit 전 | insert 한 행까지 rollback, 나머지 위와 같음 | 변화 없음 | 없음 | 같음. TX 안에서는 Outbox 1 이 보였고 rollback 뒤 0 |
| commit 뒤 relay 전 | CONFIRMED·예약 소비·Outbox PENDING(attempt 0) | 해당 record 없음 | 없음 | 같음. 새로 만든 relay 가 저장 행에서 발행 (`OrderEventRelayTest`) |
| relay 의 broker 확인 뒤 PUBLISHED 저장 전 | Outbox PENDING(attempt 1, published_at 없음) | 첫 record 있음 | consumer 가 받으면 1회 저장 | 같음. lease(sendTimeout) 뒤 재시작한 relay 가 같은 eventId 를 다른 offset 에 다시 기록, Outbox PUBLISHED(attempt 2). consumer 두 번째는 ALREADY_PROCESSED (`OutboxDeliveryE2ETest`) |
| 확인 대기 초과 뒤 늦은 성공 | PENDING·failure 1, 늦은 성공으로 바뀌지 않음 | (가짜 future) | — | 같음 |

### "PUBLISHED 를 먼저 저장하면 중복을 줄일 수 있다"의 반례

순서를 `PUBLISHED 저장 → send` 로 바꾸면 위 표의 넷째 칸(확인 뒤 저장 전 중단)의 중복은 없어진다. 대신 **저장 뒤 send 전·send 실패**에서 멈추면 Outbox 는 PUBLISHED, broker 에는 record 가 없고, relay 는 PUBLISHED 를 다시 고르지 않는다. W6 의 commit → send 누락이 그대로 Outbox 안으로 옮겨 온 것이다. 중복은 소비자의 완료 기록(eventId UNIQUE)이 흡수하지만, 누락은 아무도 다시 보내지 않는다. 그래서 상태 저장은 broker 확인 뒤에 둔다 (DR-48 (d)).

## 2. §5 W6 누락 상태 재현 → 같은 실행에서 복구 (`W6MissingEventRecoveryTest`)

역사적 W6 DB 를 보존하지 않았으므로 **새 격리 실행에서 W6 경로로 누락 상태를 다시 만든 뒤 복구**했다. 원본 사건의 복구가 아니다.

준비 (W6 경로 = api test 소스에 보존한 revision `6f7aac1` 의 `applyResult` + AFTER_COMMIT 직접 발행, Hibernate schema filter 로 `order_event_outbox` 없음):
- 기존 주문 API(`POST /api/v1/orders`, `/confirm`)와 로컬 PG fixture(`FakePaymentGateway`, 동기 SUCCESS)로 합성 주문 2건. 품목: 상품 1×2, 상품 2×1
- A 는 W6 listener 가 send 를 부르기 직전 hook 에서 멈춤(예외, listener 가 삼킴), B 는 실제 발행·소비
- topic `order-events-w6-recovery`, group `purchase-activity-w6-recovery`

| 사례 | orderId | providerOrderId | transactionKey | eventId | confirmedAt | items | 주문 | 원장 | Outbox | broker | 구매 활동 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| A 미발행 | 1 | ORDER-1 | tx-1 | order-confirmed:v1:1 | 2026-11-05T12:21:39.596647Z | 1×2, 2×1 | CONFIRMED | RESERVE −2, −1 | 테이블 없음 | 없음 | 0 행 |
| B 소비 완료 | 2 | ORDER-2 | tx-2 | order-confirmed:v1:2 | 2026-11-05T12:21:39.640269Z | 1×2, 2×1 | CONFIRMED | RESERVE −2, −1 | 테이블 없음 | `…-2@0` | 2 행 (RECORDED) |

준비 단계 PG: create 누적 **C = 2**, 외부 거래 **T = 2**, 조회 0.

전환 (초기화 없음): W6 api context 를 닫고(직접 발행 중단), 같은 DB 에 `docs/week7/order-event-outbox.sql` 실행, W7 api context 를 `ddl-auto: validate` 로 띄움. 구매 활동 consumer context 는 처음부터 끝까지 그대로.

복구:

| 단계 | A | B |
|---|---|---|
| `OrderConfirmedBackfill.backfill` | RECORDED, PENDING, confirmedAt·items 가 준비 기록과 같음 | RECORDED (W6 에서는 Outbox 가 없었으므로) |
| relay 1회차 (claimed 2, published 2) | `…-0@0`, PUBLISHED attempt 1 | `…-2@1` (재발행), PUBLISHED attempt 1 |
| consumer | `0@0` RECORDED → 활동 1×2, 2×1 | `2@1` ALREADY_PROCESSED → 활동 행·수량·완료 기록 불변 |
| 주문·원장 | 준비 때 행과 같음 (재확정·예약 추가 소비 0) | 같음 |
| broker 의 그 eventId | 1 record | 2 record (`2@0` W6, `2@1` relay) |

복구 단계 PG: create **+0** (= C), 조회·callback **+0**, 외부 거래 **+0** (= T).
같은 대상 두 번째 복구: 두 주문 모두 ALREADY_RECORDED, relay claimed 0, Outbox 2 행·broker record·구매 활동 모두 불변.
정리: 테스트가 띄운 두 앱 context 만 닫았다. 컨테이너는 이 테스트 클래스의 JVM 에만 있었고 JVM 종료 때 Testcontainers 가 정리했다.

## 3. §7 실제 주문 → Outbox → broker → 구매 활동 (`OutboxDeliveryE2ETest`)

topic `order-events-e2e-delivery`, group `purchase-activity-e2e-delivery`. 상품 7×2, 상품 8×1 (각 재고 10), 사용자 포인트 10,000.

| 관찰 | 기대 | 결과 |
|---|---|---|
| 주문·PG 거래 | 같은 확정 주문·외부 거래 각 1 | orderId 1 CONFIRMED, tx-1, PG create 1 |
| Outbox | 같은 eventId 1행, 최종 PUBLISHED | `order-confirmed:v1:1` 1행, PUBLISHED, attempt 2 |
| Kafka | 같은 eventId 의 별도 record 2개 이상 | `…-0@0`(첫 relay, PUBLISHED 저장 전 중단), `…-0@1`(재시작 relay). value 둘 다 저장 payload 와 같음 |
| consumer | 같은 사건 수신 2회 이상 | `0@0` RECORDED, `0@1` ALREADY_PROCESSED. committed offset p0 = 2 |
| 완료 이벤트 | 같은 orderId·eventId 의 완료 기록 1 | 1 |
| 구매 활동 | 상품별 2행, 수량 2·1 | (7, 2), (8, 1) |
| 재고·포인트 | 예약 추가 소비·반환 없음, 포인트 차감 없음 | 재고 8 / 9, 원장 RESERVE −2·−1 만, 포인트 10,000 |

broker 기록 2회는 애플리케이션의 send 호출 수가 아니라 broker 에서 직접 읽은 record 로 셌다 (`KafkaTestRecords.readAll`). W6 의 동일 offset 재전달(`docs/week6/kafka-observations.md` 4절)과 달리 여기서는 같은 eventId 가 별도 offset 으로 기록된다.

## 4. 운영: backlog·실패 확인과 재개

backlog (MySQL):

```sql
-- 상태별 행 수, 가장 오래 기다린 PENDING (기록 시각 기준), 누적 시도
SELECT status, COUNT(*) AS rows_, MIN(created_at) AS oldest_created, MAX(attempt_count) AS max_attempts
FROM order_event_outbox GROUP BY status;

-- 지금 보낼 차례인 PENDING (relay 가 고르는 조건과 같다, IX-15)
SELECT COUNT(*) FROM order_event_outbox WHERE status = 'PENDING' AND next_attempt_at <= UTC_TIMESTAMP(6);

-- 미해결: BLOCKED 와 오류가 남은 PENDING
SELECT event_id, order_id, status, attempt_count, failure_count, next_attempt_at, last_error
FROM order_event_outbox WHERE status = 'BLOCKED' OR (status = 'PENDING' AND failure_count > 0) ORDER BY id;
```

시각 컬럼은 UTC 로 저장된다 (`timezone.default_storage: NORMALIZE_UTC`). 로그로는 회차 요약 `OrderEvent relay 회차 [...]`, 실패 `OrderEvent 전송 실패, 재시도 예정 [eventId, attempt, error]`, 차단 `OrderEvent BLOCKED, 연속 실패 3회 [eventId, attempt, error]`, 메트릭 `order.event.send{result}` 를 본다.

PUBLISHED 는 소비 완료가 아니다. 소비 진행은 따로 본다:

```bash
docker exec -it kafka kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group purchase-activity   # LAG (docker/infra-compose.yml)
```
```sql
SELECT COUNT(*) FROM processed_order_confirmation WHERE event_id = 'order-confirmed:v1:{orderId}';
SELECT product_id, quantity FROM product_purchase_activity WHERE order_id = {orderId};
```

재개 (로컬 전용, HTTP 노출 없음). 원인을 없앤 뒤 같은 행을 다시 보낸다:

```bash
# BLOCKED → 같은 행 PENDING → 한 번 발행
./gradlew :apps:commerce-api:bootRun --args='--outbox.local.resume-event-ids=order-confirmed:v1:42'
# W6 시절 누락된 확정 주문을 지정해 Outbox 에 넣기 (보내는 것은 주기 relay)
./gradlew :apps:commerce-api:bootRun --args='--outbox.local.backfill-order-ids=41,42'
```

BLOCKED 판정은 실패 종류를 가리지 않으므로(DR-49), broker 장애가 1s·2s 백오프보다 길면 그동안의 행이 BLOCKED 가 된다. 장애 회복 뒤 위 SQL 로 BLOCKED 목록을 뽑아 재개한다. 기존 PENDING(실패 1·2회)은 자동으로 다시 나간다.

## 5. 보존·정리

- 이번 범위에서 Outbox 행은 자동 삭제하지 않는다. PENDING·BLOCKED(미발행·격리 사실)는 정리 대상이 아니다.
- PUBLISHED 정리를 도입한다면 `published_at` 기준이고, 소비자 완료 기록(TB-12 `processed_order_confirmation`)이 그보다 오래 남아야 한다: TB-12 보존 ≥ Outbox 보존 + 재발행이 일어날 수 있는 기간. Outbox 행이 없어진 뒤 같은 주문을 복구하면 같은 eventId 가 다시 나가고, 그 중복을 막는 것은 TB-12 뿐이다. 지금은 둘 다 지우지 않는다.

## 6. 한계

- **단일 relay**: 회차 겹침은 한 JVM 안의 lock 으로만 막는다. 여러 인스턴스에서 `outbox.relay.enabled=true` 면 같은 행을 동시에 보낼 수 있다 (중복은 소비자가 흡수하지만 attempt·상태 기록이 섞인다). 설정으로 한 곳에서만 켠다.
- **통제된 중단만**: "중단"은 hook 이 그 단계를 끝낸 뒤 예외를 던져 다음 단계로 못 가게 한 것이고, "재시작"은 relay 객체를 새로 만들어 같은 DB·broker·eventId 로 회차를 다시 돈 것이다. OS 강제 종료·broker 장애 주입·네트워크 분할은 시험하지 않았다.
- **단일 broker**: `acks=all` 이지만 replica 1 이라 복제 내구성은 확인하지 못했다 (W6 와 같다).
- **로컬 전체 실행 미실행**: 실제 PG 시뮬레이터로 두 앱을 띄운 확인은 W6 와 같은 이유로 하지 않았다. PG 는 로컬 fixture(`FakePaymentGateway`)다.

# 주문 확정 이벤트와 구매 활동 설계 — Week 6

> 상태: **구현 중 (2026-10-30)**. producer와 consumer, 전달 실험을 구현하고 있다. 이 문서는 이벤트 계약·경계·실패 지점·결정 사항과 테스트 구성을 정한다.
> 5주차의 결제 흐름(`docs/week5/pg-contract.md`, `design.md` 9절)은 바꾸지 않고, 확정된 뒤의 사실을 이벤트로 내보내는 일만 더한다.

## 0. 범위

| 이번 주에 하는 것 | 하지 않는 것 |
|---|---|
| 검증된 PG SUCCESS로 주문이 **처음** CONFIRMED가 된 사실을 `order-events`로 발행 | 새 결제·상품 CRUD |
| `commerce-streamer`가 이벤트를 소비해 상품 구매 활동을 DB에 한 번만 저장 | 랭킹 API (9주차) |
| partition·consumer group·offset 실험으로 전달 상태 설명 | Outbox (7주차). DB와 broker가 원자적이지 않다는 사실만 재현해 남긴다 |

## 1. 흐름과 경계

```
검증된 PG SUCCESS
  → [TX B] 주문 최초 CONFIRMED + confirmedAt 기록 + 예약 소비 + 이벤트 객체 구성   ← DB commit
  → (commit 뒤) PaymentFacade가 발행                                                  ← 여기까지 commerce-api
  → Kafka order-events (key = orderId)
  → commerce-streamer record listener
  → [TX] 완료 기록 + 상품별 구매 활동 저장 → commit 뒤 ack                           ← commerce-streamer
```

| 경계 | 지키는 것 | 깨지는 시점 (실패 지점) |
|---|---|---|
| 주문 DB TX B | 주문 CONFIRMED·예약 소비·확정 시각이 함께 commit되거나 함께 rollback | rollback이면 이벤트가 만들어지지 않는다 |
| DB commit → broker | **없음.** 둘을 묶는 장치가 없다 | commit 뒤 send 전에 멈추거나 send가 실패하면 확정 주문은 남고 이벤트는 없다 (W7 Outbox의 출발점) |
| broker → consumer | 한 번 이상 전달 (at-least-once) | 처리 뒤 offset commit 전에 멈추면 같은 record가 다시 온다 |
| consumer DB TX | 완료 기록과 모든 품목이 함께 commit 또는 rollback | 중간 실패는 전체 rollback, 재전달로 다시 시도 |

이벤트가 필요한 이유: 주문 확정(commerce-api)과 구매 활동 집계(commerce-streamer)를 한 트랜잭션으로 묶으면 두 앱이 같은 DB·배포 단위에 묶이고 집계 지연이 결제 응답에 영향을 준다. 사실(확정)만 넘기고 집계는 각자 속도로 따라가게 한다.

## 2. 이벤트 계약

| 항목 | 계약 |
|---|---|
| topic | `order-events` (`order-event.topic-name`). 테스트는 고유 접미사를 붙여 격리 |
| eventType / schemaVersion | `OrderConfirmed` / `1` |
| key | 내부 orderId의 문자열 표현. 같은 주문은 같은 key |
| eventId | `order-confirmed:v1:{orderId}`. 한 주문의 단 한 번인 확정 사실에서 유도. 재발행·재전달마다 새로 만들지 않는다 |
| orderId / confirmedAt | 확정 주문 id / 최초 PG 성공 전이 시각(UTC, ISO-8601, 밀리초 정밀도). 저장된 값과 같다 |
| items | `productId`, `quantity`. 같은 상품은 합산해 하나로, 상품 id 오름차순, 수량 양수 |
| 담지 않는 것 | 카드·PG 거래 정보·사용자 정보 |

예시(주문 42):

```json
{
  "eventType": "OrderConfirmed",
  "schemaVersion": 1,
  "eventId": "order-confirmed:v1:42",
  "orderId": 42,
  "confirmedAt": "2026-09-07T01:00:00Z",
  "items": [{"productId": 7, "quantity": 2}, {"productId": 8, "quantity": 1}]
}
```

- 값은 합성 예시다. 실제 발행은 저장된 주문의 확정 시각·품목을 쓴다. 소비 시점에 상품을 다시 조회해 과거 사실을 덮어쓰지 않는다.
- 속성 순서·items 순서가 다르다고 다른 사실로 보지 않는다. 비교는 정규화한 값으로 한다(consumer).
- 고객이 이벤트를 발행하는 공개 endpoint는 만들지 않는다.

## 3. producer (commerce-api) 결정

### 3-1. 정책 결정 (2026-10-29 확정)

| 질문 | 결정 | 이유 |
|---|---|---|
| 발행 위치 | `PaymentFacade`가 결과 적용 TX가 commit된 **뒤** 직접 호출 | `settle()`은 트랜잭션 메서드라서 안에서 send하면 commit 전 발행이다. confirm·reconcile·callback이 모두 `applyCreate`/`applyLookup`을 거치므로 한 지점(`publishAfterCommit`)으로 모인다. after-commit listener보다 "이번에 처음 확정했는가"를 호출 결과로 바로 알 수 있다 |
| "처음 확정"의 판별 | `PaymentEffect.SUCCEEDED`일 때만 `PaymentTransactions.Applied.confirmedEvent`가 채워진다 | 시도가 이번 호출에서 종결로 바뀐 경우에만 SUCCEEDED다. 같은 결과를 다시 적용하면 NONE이라 새 이벤트가 없다 |
| `confirmedAt` 저장 | `OrderModel.confirmed_at`(Instant, null 허용). `completePgPayment`에서만 기록 | 결제 대기에서만 들어올 수 있어 한 번만 정해진다. 이 컬럼이 생기기 전에 POINT로 확정한 주문은 null이고 소급하지 않는다 |
| 이벤트 구성 시점 | TX 안에서 저장된 엔티티로 만든다 | commit 뒤 lazy 접근에 의존하지 않고, 소비 시점 조회로 사실을 바꾸지 않는다 |
| 발행 실패 표시 | 주문 결과는 저장된 대로 응답하고, 실패는 ERROR 로그(eventId·orderId·이유)로 드러낸다 | 이미 확정된 주문을 실패·미확인으로 되돌리거나 PG create를 다시 부르지 않는다. 재시도·보관은 W7 몫 |

### 3-2. 발행 규칙

| 상황 | 발행 |
|---|---|
| 검증된 PG SUCCESS로 처음 CONFIRMED (callback·reconcile·create 응답 어느 경로든) | 한 번 |
| DRAFT·PAYMENT_PENDING·UNKNOWN·확정 실패(PAYMENT_FAILED) | 안 함 |
| 확정 TX rollback | 안 함 (이벤트를 commit 전에 보내지 않는다) |
| 이미 종결된 같은 시도의 반복 적용 (callback·reconcile 중복) | 안 함 |
| 이전 POINT 확정 주문 | 안 함 (소급 없음, `confirmed_at`이 null) |

### 3-3. 계층

| 층 | 구성 | 비고 |
|---|---|---|
| domain.order | `OrderConfirmedEvent`, `OrderEventPublisher`(port), `OrderEventPublication`(Sent/Failed) | PG port(`PaymentGateway`)와 같은 방식. domain은 Kafka를 모른다 |
| application.payment | `PaymentFacade.publishAfterCommit`, `PaymentTransactions.Applied` | 발행기가 던져도 주문 응답을 바꾸지 않는다 |
| infrastructure.order | `KafkaOrderEventPublisher` | send 결과(topic·partition·offset)를 기다려 `Sent`로, 실패·timeout은 `Failed`로 돌려준다. 예외를 던지지 않는다 |

send를 기다리는 시간은 `order-event.send-timeout`(5초)이다. 기다리는 동안 DB 트랜잭션·재고 잠금은 이미 풀려 있다(commit 뒤).

## 4. Kafka 설정 확인과 정리

시작 저장소의 `kafka.yml`에서 고친 것:

| 항목 | 시작 상태 | 정리 |
|---|---|---|
| consumer value 역직렬화 | `value-serializer`로 적혀 있어 Spring Boot가 읽지 않는다 | `value-deserializer: ByteArrayDeserializer` |
| auto commit | `properties.enable-auto-commit`(하이픈 표기)은 Kafka 키가 아니다 | `consumer.enable-auto-commit: false`. 실제 `ConsumerConfig` 값은 listener 테스트에서 확인한다 |
| bootstrap 주소 | admin만 `kafka:9092`, 나머지는 `localhost:19092`로 섞여 있었다 | 하나의 호스트 주소만 쓴다 (컨테이너 내부 주소와 섞지 않는다) |
| producer | acks·멱등·timeout 미지정 | `acks=all`, `enable.idempotence=true`, `max.block.ms=5000`, `delivery.timeout.ms=30000` |

기존 batch listener factory(`BATCH_LISTENER`: batch·MANUAL·concurrency 3)는 바꾸지 않는다. 이번 이벤트용 record factory(`orderActivityKafkaListenerContainerFactory`)는 `commerce-streamer`에 따로 둔다.
테스트 broker는 `modules:kafka` testFixtures의 `KafkaBrokerFixture`(Testcontainers, 단일 노드)이고 처음 쓸 때만 컨테이너를 띄운다. 고유 topic·group을 쓰며 다른 실행의 topic·group을 지우지 않는다.

`acks=all`은 단일 broker에서 복제 장애 보장을 확인하지 못한다. 이 환경의 테스트가 증명하는 것은 "broker가 알려 준 위치에서 같은 record를 읽는다"까지다.

## 5. consumer (commerce-streamer)

### 5-1. 저장과 정합성

| 테이블 | 보호하는 규칙 |
|---|---|
| `processed_order_confirmation` | `event_id` 유일, `order_id` 유일, 정규화한 사실의 `fingerprint`(SHA-256) |
| `product_purchase_activity` | `(order_id, product_id)` 유일, `quantity`·`confirmed_at` 보존. 상품 행을 참조하지 않는다 |

- 완료 기록과 모든 품목은 한 DB TX(`PurchaseActivityRecorder.record`)에서 저장한다. 중간 품목이 실패하면 완료 기록과 앞서 저장한 품목도 rollback된다.
- 같은 eventId·같은 사실의 재전달은 정상 no-op(`ALREADY_PROCESSED`)이다. 같은 eventId에 다른 수량·확정 시각이 오거나 같은 orderId에 다른 eventId가 오면 `OrderEventConflictException`으로 드러내고 활동을 더하지 않는다.
- 중복 판단은 먼저 조회하는 것으로 끝내지 않는다. 조회는 흔한 재전달을 빨리 거르는 용도이고, 동시 소비는 DB 유일 제약이 막는다. 유일 제약에 걸려 TX가 rollback되면(`PurchaseActivityHandler`) **새 TX**에서 기존 완료 사실이 같은지 확인한다. 같으면 no-op, 다르면 충돌, 기존 사실이 없으면 "중복"으로 바꾸지 않고 원래 무결성 오류를 그대로 올린다.
- 완료 기록·활동 저장은 `Handler`(TX 없음) → `Recorder`(TX) 두 빈으로 나눠, 같은 객체 안에서 부르면 TX가 적용되지 않는 문제를 피한다.
- 삭제·비노출 상품을 다시 노출하지 않는다. 소비자는 상품 테이블을 읽지 않고 과거 주문의 `productId`를 식별자로만 보존한다.

### 5-2. 이벤트 검증

`OrderConfirmedParser`가 계약(eventType·schemaVersion·정수 id·ISO 시각·key와 orderId 일치·양수 수량)을 확인한다. 계약을 지키지 않으면 `InvalidOrderEventException`이고, 모르는 eventType·schemaVersion을 건너뛰지 않는다(건너뛰면 구매 활동에서 조용히 빠진다). 계약을 어긴 record가 한 건 있으면 그 partition의 소비가 거기서 멈춘다. 데이터 오류를 자동으로 버리는 정책은 이번 범위가 아니다.

### 5-3. listener 설정

| 설정 | 값 | 이유 |
|---|---|---|
| 단위 | record (`batchListener=false`) | 한 DB TX와 ack가 한 record에 대응 |
| ack mode | `MANUAL_IMMEDIATE` | handler가 DB commit을 끝낸 뒤 `acknowledge()`를 부르는 순간 그 offset을 commit |
| `enable.auto.commit` | `false` (실제 `ConsumerConfig` 값으로 검증) | 자동 commit은 DB 완료와 무관하게 offset을 올린다 |
| error handler | `CommonContainerStoppingErrorHandler` | 실패 record를 건너뛰지 않고 컨테이너를 멈춘다. 실패 record의 offset은 commit되지 않고, 고친 뒤 같은 group으로 재시작하면 같은 record가 다시 온다 |
| `concurrency` | 1 | record를 순서대로 처리 |
| `auto.offset.reset` | `earliest` | 새 group이 이미 쌓인 확정 이벤트를 건너뛰지 않게 한다(공통 설정의 latest는 새 group의 과거 이벤트를 버린다) |
| fetch | `fetch.min.bytes=1`, `fetch.max.wait.ms=500` | 공통 batch factory의 1MiB·5초는 적은 양의 record를 최대 5초 늦게 보이게 한다. 확정 이벤트는 한 건씩 오므로 짧게 둔다. 긴 sleep으로 덮지 않는다 |

listener는 실패를 로그(ERROR, topic·partition·offset)로 남기고 예외를 그대로 올린다. 삼키지 않는다.

## 6. 테스트

### 6-1. producer (commerce-api)

| 테스트 | 확인 |
|---|---|
| `OrderConfirmedEventTest` | `confirmedAt` 기록 규칙, 이벤트가 저장 사실에서 만들어짐, eventId·key 안정성, 품목 합산·정렬, 확정 시각 없는 주문 거부 |
| `OrderConfirmedPublicationTest` | 처음 확정에서 한 번 발행, 중복 적용 무발행, 대기·실패·rollback 무발행, 발행 실패 시 주문·예약·PG 호출 유지 |
| `KafkaOrderEventPublisherTest` | 실제 broker에 보내고 broker가 알려 준 위치에서 key·본문 읽기, 같은 key는 같은 partition, broker 불통 시 예외 없이 실패 반환 |

통합 테스트는 broker 자리에 발행 호출을 기록하는 `RecordingOrderEventPublisher`를 쓴다. 실제 broker 전송은 `KafkaOrderEventPublisherTest`가 맡는다.

### 6-2. consumer (commerce-streamer)

| 테스트 | 확인 |
|---|---|
| `OrderConfirmedParserTest` | 계약 예시 읽기, 순서·분할이 달라도 같은 fingerprint, 계약 위반 거부 |
| `OrderConfirmedListenerTest` | handler 뒤에 ack, handler 실패·충돌·검증 실패는 ack 안 함 |
| `PurchaseActivityIntegrationTest` | 저장 규칙(주문 42·43), 재전달 no-op, 충돌 2종, 중간 품목 실패 rollback, 동시 중복 |
| `OrderActivityKafkaConfigTest` | 실제 ConsumerConfig 값, 세 클라이언트의 bootstrap 일치, 컨테이너 설정 |

### 6-3. 전달 실험 (`*KafkaDeliveryTest`, 실제 broker)

| 테스트 | 확인 |
|---|---|
| `PartitionAssignmentKafkaDeliveryTest` | partition 2개, 같은 group의 consumer 1→2→3 배정표(broker가 본 값), 명시 partition 전송·수신, 같은 key는 같은 partition |
| `OffsetRedeliveryKafkaDeliveryTest` | 실험 A: DB commit 뒤 offset을 commit하지 않고 종료하면 같은 위치에서 재전달되고 구매 효과는 한 번(committed offset n → n → n+1) |
| `ListenerFailureKafkaDeliveryTest` | 실험 B: DB commit 전 오류 시 전체 rollback·offset 미진행·listener 중단·ERROR 로그, 오류 제거 후 같은 group으로 재시작하면 재전달·저장·ack |

## 7. 알려진 한계

- commit 뒤 send 전에 멈추거나 send가 실패하면 이벤트가 사라진다. 자동 재발행이 없고, 구매 활동이 누락된다. 이 구간을 재현해 W7(Outbox)의 출발점으로 남긴다.
- 단일 broker라 복제·리더 선출 장애에서의 전달 보장은 확인하지 못한다.
- `commerce-api`가 `modules:kafka`에 의존하게 되어 `KafkaConfig`(기존 batch factory 포함)도 로드된다. api에는 listener가 없어 영향이 없다.

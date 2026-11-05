# 주문 확정 이벤트 Producer 설계

`commerce-api`는 검증된 PG 성공을 최초 주문 확정으로 반영하고, 같은 DB 트랜잭션에서 `OrderConfirmed` Outbox를 기록한다. 단일 polling Relay가 저장된 key·payload를 Kafka에 발행한다. payload·key·eventId·버전 정책은 [공통 이벤트 계약](../../../docs/system-design/use-cases/order-confirmed-event.md#이벤트-계약)을 따른다. Kafka topic과 연결 주소는 [Broker 설계](../../../modules/kafka/docs/broker.md)를 따른다.

## 책임과 완료 기준

주문 확정과 이벤트 전달의 완료 기준을 분리한다. 주문 확정의 기준은 TX B의 DB commit이고, Relay 발행의 기준은 broker 성공 응답과 그 결과를 기록한 Outbox 상태 TX다. Consumer의 구매 활동 저장은 이 앱의 완료 조건에 포함하지 않는다.

TX B는 주문 확정과 전달할 메시지를 Outbox에 함께 저장한다. Relay는 `PENDING` 행을 찾아 broker에 보내고 성공 확인 뒤 `PUBLISHED`를 기록한다. 발행 결과가 불확실하거나 상태 저장에 실패하면 같은 메시지를 다시 보낼 수 있으므로 중복 record를 허용한다.

## 이벤트 구성

애플리케이션 계층은 저장된 주문 사실로 메시지를 구성하고 JSON 문자열로 직렬화해 Outbox에 저장한다. 값의 정의는 [공통 이벤트 계약](../../../docs/system-design/use-cases/order-confirmed-event.md#이벤트-계약)을 따른다. Relay는 현재 상품 정보를 다시 조회하지 않고 저장된 eventId·key·payload를 그대로 발행한다.

1. `eventId`는 orderId에서 계산하므로 producer에 별도 eventId 컬럼 없이 같은 ID를 재구성한다.
2. `confirmedAt`은 최초 PG 성공 TX B에서 마이크로초로 자른 시각을 주문 상태와 함께 저장하고, 중복 callback·reconcile에서 바꾸지 않는다. 발행·처리 시각을 넣지 않는다.
3. `items`는 주문 생성 때 상품별로 합산된 스냅샷을 `productId` 오름차순으로 정렬한다.

`OrderConfirmedMessageV1`은 application 계층에서 구성하는 내부 메시지 계약이다. V2 명세가 생기면 별도 DTO와 serializer 경로를 추가한다. 다른 앱의 DTO를 import하거나 비즈니스 DTO를 공통 Kafka 모듈로 옮기지 않는다.

## 발행 조건

결과 반영에서 시도를 `SUCCEEDED`로 종결하고 주문을 `PAYMENT_PENDING → CONFIRMED`로 바꾼 조건부 갱신이 1행인 요청만 이벤트를 만든다. 생성 응답, callback, reconcile, 주기 조회가 모두 같은 TX B를 지나므로 경로와 관계없이 같은 조건이다.

| 상황 | Outbox 기록 |
| --- | --- |
| 최초 PG 성공 확정 | TX B에서 OrderConfirmed Outbox PENDING 한 행을 함께 commit |
| 처리 중·결과 미확인(`PENDING`·`UNKNOWN`), 충돌 | 없음 |
| 확정 실패(`PAYMENT_FAILED`) | 없음 |
| 최초 확정 TX B rollback | 없음 |
| 이미 종결된 시도에 같은 결과 반복 (중복 callback, reconcile, 주기 조회) | 없음. 조건부 갱신 0행 |
| 이전 POINT 확정 주문, 이번 변경 전 PG 확정 주문 | 없음. 소급 발행하지 않는다. |

## 발행 위치

`PaymentResultTransaction.completeOrder`(TX B)는 최초 성공 조건을 확인한 뒤, 저장한 품목 스냅샷과 확정 시각으로 `OrderConfirmedMessageV1`과 JSON payload를 구성한다. Outbox insert는 주문·결제 시도 결과와 같은 데이터 소스의 같은 로컬 TX에 참여한다. serialization이나 Outbox 저장에 실패하면 주문 확정도 rollback한다. commit 뒤에는 Scheduler가 Relay를 호출하므로 주문 확정 요청 스레드에서 Kafka send를 기다리지 않는다.

Relay는 짧은 DB TX로 기한이 된 행을 조회하고 시도 횟수를 기록한 다음, DB 트랜잭션 밖에서 Kafka에 보낸다. broker 성공 Future가 완료되면 별도 DB TX에서 `PUBLISHED`와 `publishedAt`을 저장한다. 조회·시도·상태 저장의 TX와 외부 전송 대기를 분리한다.

## 전송 결과와 실패

Relay는 Kafka `send()`가 반환하는 `CompletableFuture`의 성공으로 broker 확인을 판단한다. 현재 기본 모드는 제한 시간 동안 결과를 기다리는 `BOUNDED_WAIT`다. 전송 worker 안에서 결과를 확인하고 성공 상태를 갱신하므로 처리 흐름과 실패 전이를 따라가기 쉽다.

`CALLBACK` 비교 모드도 구현한다. Future 완료 뒤 상태 저장을 전용 단일 executor로 넘기고, 완료까지 Relay 실행 표시를 유지해 스케줄러 tick이 겹쳐도 중복 실행을 시작하지 않는다. 같은 직렬 Relay에서 callback은 다음 메시지를 병렬로 보내지 않으므로 처리량 이득이 없고 생명주기 처리가 늘어난다. 비교 테스트는 callback Future를 통제해 반환 순서를 확인하며 실제 네트워크 지연 성능 비교로 해석하지 않는다.

| 결과 | Outbox 처리 |
| --- | --- |
| broker 성공 확인 + 상태 TX 성공 | `PUBLISHED`와 `publishedAt` 저장. 로그에 topic·partition·offset 기록 |
| 일시 전송 실패·timeout | 같은 행을 `PENDING`으로 유지하고 오류·다음 시각 기록. 결과가 불확실하면 broker에 record가 남았을 수도 있음 |
| broker 성공 뒤 상태 TX 실패 | `PENDING`이 남으면 같은 메시지를 재발행할 수 있음 |
| 지원하지 않는 형식 등 영구 오류 | `BLOCKED`로 남기고 자동 전송을 멈춤. 다른 행 처리는 계속한다. |

- 첫 send가 시도 1회다. 현재 설정은 한 재시도 주기에서 총 세 번 시도한 뒤 `BLOCKED`로 전환한다. 담당자가 원인을 해결하면 같은 행을 수동 재개하고, 누적 attemptCount는 유지하되 주기 횟수만 초기화한다.
- 일시 실패 행은 재시도 시각 전까지 대기한다. 세 번째 실패로 `BLOCKED`가 되면 같은 묶음의 다음 이벤트를 처리한다. 제한 대기 중인 행은 현재 회차를 끝내고 다음 tick에서 다시 처리한다.
- 실패나 timeout만으로 broker record의 부재를 단정하지 않는다. PUBLISHED는 broker 성공을 Relay가 확인하고 DB에 기록했다는 뜻이며, Consumer의 구매 활동 완료를 뜻하지 않는다.
- 주문·Outbox의 일관성을 잃지 않도록 Relay 재시도는 새 eventId를 만들지 않는다. 기록된 payload·key를 그대로 사용한다.

`BOUNDED_WAIT`는 제한 시간 동안 Relay worker를 점유한다. 현재 직렬 처리량에서는 단순한 완료 흐름을 우선했으며, batch·timeout·backlog를 관찰해야 한다. callback은 별도 처리량이 필요해질 때 전용 executor와 상태 생명주기를 포함해 다시 평가한다.

## 발행 누락 구간

주문·Outbox 기록과 broker 기록은 같은 원자적 작업이 아니다. TX B commit 뒤 Relay 실행 전 프로세스가 멈춰도 주문과 `PENDING` Outbox는 DB에 남고, Relay 재시작 뒤 대상을 다시 찾을 수 있다.

send 이후 오류·timeout을 관찰하거나 broker 성공 뒤 상태 저장이 실패하면 broker 기록 여부가 불명확할 수 있다. Relay는 같은 행을 재발행할 수 있으므로 Kafka에 중복 record가 생길 수 있고, Consumer Inbox가 업무 중복 효과를 막는다.

Outbox는 broker가 영구적으로 접근 불가능한 상황, payload 호환성 오류, Outbox 저장소 손실, 행의 임의 삭제를 자동으로 복구하지 않는다. `BLOCKED` 작업은 원인을 확인할 담당자와 재개 절차가 필요하다.

## 누락 판단과 관찰 범위

검증은 [발행 조건](#발행-조건)에 해당하는 확정 주문에서 출발해 Kafka record와 소비 완료 기록·구매 활동을 대조한다. 소급 발행하지 않는 이전 확정 주문은 대상에서 제외한다. 구매 활동은 주문당 한 행이 아니라 품목별 행이므로, 행 수만 비교하지 않고 상품 집합·수량·확정 시각까지 원본과 비교한다.

| 관찰 | 판단 |
| --- | --- |
| 같은 `eventId`의 record 있음, 소비 완료 기록과 구매 활동 없음 | 발행은 확인했지만 소비는 미완료다. 소비 지연·중단·저장 실패와 실제 committed offset을 확인한다. |
| record 있음, 소비 완료 기록과 모든 구매 활동이 원본과 일치 | 전달과 업무 처리를 확인했다. |
| record를 확인하지 못했고 소비 완료 기록과 구매 활동도 없음 | 미반영 후보다. 이 관찰만으로 미발행을 확정하지 않고 전송 경계와 broker의 조사 범위를 확인한다. |

- 소비 지연을 고려해 정해진 기한 안에서 관찰한다. 구매 활동이 즉시 보이지 않는다는 이유만으로 유실로 판단하지 않는다.
- 실패 로그가 없다는 것은 발행 성공의 증거가 아니다. send 전 중단은 실패 콜백에도 나타나지 않는다.
- consumer lag가 0이어도 발행 누락은 발견할 수 없다. broker에 들어오지 않은 이벤트는 group의 offset이나 lag에 포함되지 않는다.
- Kafka record의 존재는 보존 범위 안에서 확인한다. retention으로 이미 삭제된 record가 현재 없다는 사실만으로 과거 미발행을 증명하지 않는다.
- 원본 대조는 테스트와 검증용으로 수행한다. commerce-streamer의 정상 소비 경로에서 주문 테이블을 조회하지 않으며, 주기적 자동 대조와 보충 발행은 이번 범위에 넣지 않는다.

자동 대조나 임의 재발행은 하지 않는다. `BLOCKED` 행은 원인과 `eventId`를 확인한 담당자가 로컬 전용 재개 진입점으로 같은 Outbox 행을 다시 `PENDING`으로 열고 Relay를 한 번 실행한다. 이미 종결한 결제의 reconcile은 전송 복구 수단이 아니다. broker record가 남아 있을 가능성이 있으면 재개 뒤 중복 record가 생길 수 있고, Consumer Inbox가 같은 `eventId`의 중복 업무 효과를 막는다.

### 운영 조회와 알림

Relay는 `order_confirmed_outbox.pending`, `order_confirmed_outbox.blocked`, `order_confirmed_outbox.oldest_pending_age_seconds` gauge와 `outbox_blocked_transitions_total` counter를 제공한다. Grafana에서는 backlog와 장기 대기를 대시보드로 보고, 새로운 `BLOCKED` 전환과 장기 대기를 Slack 알림으로 연결한다. `BLOCKED` 현재 건수는 미해결 작업의 규모를 나타내며, 전환 counter는 새로 막힌 시점을 찾는 용도다. 애플리케이션이 Slack webhook을 직접 호출하지 않는다.

SQL로 행과 오류를 확인할 때는 다음 조회를 사용한다.

```sql
SELECT status, COUNT(*) AS row_count
FROM order_confirmed_outbox
GROUP BY status;

SELECT event_id, order_id, attempt_count, cycle_attempt_count,
       next_attempt_at, last_error, created_at
FROM order_confirmed_outbox
WHERE status IN ('PENDING', 'BLOCKED')
ORDER BY CASE status WHEN 'BLOCKED' THEN 0 ELSE 1 END,
         next_attempt_at, id;
```

`attempt_count`는 실제 broker 수신 수가 아니라 Relay가 시작한 시도 수다. send 전에 프로세스가 멈춰도 증가할 수 있고, timeout 뒤 broker record가 남았을 수도 있다. Outbox와 Inbox는 자동 삭제하지 않는다. 정리 정책을 추가할 때는 Kafka에서 다시 읽을 수 있는 기간보다 Inbox의 중복 방지 기록을 짧게 보존해 중복 구매 효과가 생기지 않도록 함께 정한다.

## Producer 설정

공통 [kafka.yml](../../../modules/kafka/src/main/resources/kafka.yml)의 producer 설정을 사용한다.

| 설정 | 값 | 의미와 한계 |
| --- | --- | --- |
| `acks` | `all` | 현재 ISR의 기록을 확인한 뒤 성공으로 본다. 단일 broker의 한계는 [Broker 설계](../../../modules/kafka/docs/broker.md#보존과-복제의-한계)를 따른다. |
| `enable.idempotence` | `true` | 같은 producer 세션의 내부 전송 재시도로 생기는 중복을 막는다. Relay가 의도적으로 다시 보낸 동일 메시지는 합치지 않는다. |
| key/value serializer | `StringSerializer` / `StringSerializer` | Outbox에 저장한 key와 JSON 문자열을 변경 없이 전송한다. |
| `request.timeout.ms` / `delivery.timeout.ms` | `2000` / `5000` | broker 요청과 producer 전달 시도의 timeout이다. Relay의 Future 대기 설정과 별도로 관찰한다. |
| `max.block.ms` | `1000` | metadata·buffer 확보를 위한 동기 send 진입 대기 상한이다. 주문 확정 TX가 아닌 Relay 흐름에서 대기한다. |
| Future timeout | `6000` | Relay가 완료 응답을 기다리는 제한 시간이다. timeout이어도 broker에 record가 없었다고 단정하지 않는다. |
| batch / poll / retry interval | `10` / `1s` / `5s` | 한 회차의 조회 크기와 polling·재시도 주기. 운영 처리량에 맞춰 조정할 설정값이다. |

이 값들로 API 응답 시간이나 복구 시간을 보장한다고 해석하지 않는다. 멱등 producer는 `acks=all`, 양수 retries, 허용되는 in-flight 수의 조합을 요구한다. 현재 in-flight 수는 client 기본값 5를 사용한다.

## 검증

테스트 위치·방법·기대값은 [공통 검증 문서](../../../docs/system-design/use-cases/order-confirmed-event-verification.md)를 따른다.

주요 구현은 [PaymentResultTransaction](../src/main/java/com/loopers/application/payment/PaymentResultTransaction.java), [OrderConfirmedOutboxJpaEntity](../src/main/java/com/loopers/infrastructure/order/outbox/OrderConfirmedOutboxJpaEntity.java), [OrderConfirmedOutboxRelay](../src/main/java/com/loopers/infrastructure/order/outbox/OrderConfirmedOutboxRelay.java), [OrderConfirmedKafkaSender](../src/main/java/com/loopers/infrastructure/order/outbox/OrderConfirmedKafkaSender.java)다.

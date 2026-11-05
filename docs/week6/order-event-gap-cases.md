# 주문 확정 이벤트 누락 구간 재현 (과제 실험 ④)

주문 DB commit과 Kafka send 사이에는 원자성이 없다. 이 문서는 그 구간에서 이벤트가 빠지는 사례(A)와 정상 사례(B)를 같은 조건으로 재현한 기록이다. 7주차 Outbox 작업은 이 기록에서 출발한다.

## 역할

| 사례 | 역할 |
| --- | --- |
| A (미발행) | 주문은 확정·예약 소비까지 commit됐는데 그 확정의 OrderConfirmed가 broker에 없다. 3b 대사·7주차 Outbox가 메워야 하는 구간 |
| B (정상) | 같은 경로로 확정하고 broker까지 보낸 기준 사례 |

## 재현 조건

- 사용한 커밋: `ecf41f6` (`OrderConfirmedGapCaseTest`), 발행 구현은 `237e8c6`·`408bf6b`. 사례 B fixture 비교 단언과 streamer 소비 테스트는 `77f734e`
- 실행: `./gradlew :apps:commerce-api:test --tests '*OrderConfirmedGapCaseTest' -i` 후 `[실험④ 사례 A]`·`[실험④ 사례 B]` 줄 확인
- 공통: 고정 Clock `2026-10-29T01:00:00Z`, 상품 2개(재고 각 5), 주문 품목 첫 상품×2·둘째 상품×1. 기존 주문 API(`POST /api/v1/orders/{id}/confirm`) → 로컬 테스트 PG 서버(create 즉시 SUCCESS) → 실제 결과 반영 경로로 확정한다.
- broker: Testcontainers `apache/kafka:3.8.1`, 테스트마다 고유 접미사를 붙인 단일 partition topic. 고유 group의 테스트 consumer로 읽는다.
- 사례 A의 멈춤: 테스트 설정에서 `OrderConfirmedPublisher`를 대역으로 바꿔 끼워, commit 뒤 after-commit listener가 부른 send 직전에 payload만 남기고 보내지 않는다. 운영 코드에는 분기가 없다. 프로세스를 강제 종료한 것이 아니라, commit 뒤 send 전에 멈춘 상황을 흉내 낸 것이다.
- 사례 A의 "없음": 멈춤을 풀고 다른 주문(경계)을 같은 경로로 확정해 그 record가 도착할 때까지 읽은 뒤 판단한다. 한 partition 안에서는 같은 producer가 보낸 순서대로 쌓이므로, 대상 record가 보내졌다면 경계보다 앞에 있어야 한다.

## 결과

카드 번호·PG 비밀은 적지 않는다. 확정 시각은 DB(UTC) 값이다.

| 항목 | 사례 A (미발행) | 사례 B (정상) |
| --- | --- | --- |
| orderId | 1 | 1 |
| providerOrderId | `LP-ORD-0000000001` | `LP-ORD-0000000001` |
| transactionKey | `TR-000001` | `TR-000001` |
| eventId | `order-confirmed:v1:1` | `order-confirmed:v1:1` |
| confirmedAt | `2026-10-29T01:00:00Z` | `2026-10-29T01:00:00Z` |
| items | 상품 1×2, 상품 2×1 | 상품 1×2, 상품 2×1 |
| 주문 | `CONFIRMED` | `CONFIRMED` |
| 결제 기록 결과 / PG 상태 | `SUCCESS` / `SUCCESS` | `SUCCESS` / `SUCCESS` |
| 예약(재고 첫/둘째, 시작 5/5) | 3/4 (소비 유지) | 3/4 (소비 유지) |
| broker | 그 eventId record 없음. 경계까지 읽은 eventId는 `order-confirmed:v1:2`뿐 | (topic=`order-events-it-…`, partition=0, offset=0), key=`1` 1건 |
| 구매 활동(streamer 실제 소비) | 없음(대사 전) | 완료 기록 1행, 구매 활동 2행(상품 1×2, 상품 2×1, confirmedAt 2026-10-29T01:00:00Z), group `purchase-activity` committed 보내기 전 없음 → 처리 후 n+1 |

사례 B의 record 값:

```json
{"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:1","orderId":1,"confirmedAt":"2026-10-29T01:00:00Z","items":[{"productId":1,"quantity":2},{"productId":2,"quantity":1}]}
```

테스트마다 테이블을 비워 주문 ID가 1부터 다시 시작하므로 두 사례의 ID가 같다. topic 이름의 접미사는 실행마다 바뀐다.

## 소비 쪽까지의 연결

사례 B의 record 값을 fixture `modules/kafka/src/testFixtures/resources/contracts/order-confirmed-gap-case-b.json`으로 고정했다. 위 "사례 B의 record 값"과 같은 한 줄이고, key는 `1`이다.

- api `OrderConfirmedGapCaseTest` 사례 B: broker에서 읽은 record의 value를 JSON으로 읽은 값이 이 fixture와 같고(정규화 비교), key가 `"1"`이다.
- streamer `OrderConfirmedGapCaseConsumedTest`: 이 fixture 값을 key `1`로 고유 topic에 보내고, 실제 `OrderEventsListener`(운영 group `purchase-activity`)가 소비하게 한다. handler는 직접 부르지 않는다. 결과는 위 표의 "구매 활동(streamer 실제 소비)" 행이다. 완료 기록 1행, 구매 활동 2행이 남고, 이상 기록은 0행이다. committed offset은 보내기 전 없음에서 처리 후 n+1이 된다. 이 listener는 DB commit 뒤에만 ack하므로, committed n+1은 저장이 끝났다는 뜻이기도 하다.
  - 실행: `./gradlew :apps:commerce-streamer:test --tests '*OrderConfirmedGapCaseConsumedTest' -i` 후 `[실험④ 사례 B 소비]` 줄 확인
- 보조 근거: 주문 42 공유 계약 fixture(`modules/kafka/src/testFixtures/resources/contracts/order-confirmed-v1.json`)를 쓰는 두 `OrderConfirmedContractTest`.
  - api 쪽은 팩토리·직렬화가 같은 주문에서 만든 메시지가 fixture와 정규화해서 같음을 보인다.
  - streamer 쪽은 같은 fixture를 파싱·처리하면 구매 활동 2행이 남음을 보인다.
- 증명하지 못하는 것: api와 streamer를 한 실행에서 함께 띄우지 않는다. streamer 테스트의 record는 api producer가 아니라 테스트 producer가 보낸다. 두 테스트를 잇는 것은 같은 fixture다.

사례 A의 확정은 broker에 없으므로 구매 활동에 영영 나타나지 않는다. 대사가 재발행해야만 나타난다.

## 남는 한계

- 사례 A의 확정은 지금은 누구도 다시 보내지 않는다. 실패 지표·ERROR 로그도 남지 않는다. send 실패가 아니라 send가 아예 없었기 때문이다. 3b 대사(`(status, confirmed_at)` 범위 조회 → 같은 payload 생성 코드로 재발행)가 이 구간을 메운다.
- 7주차 Outbox는 확정과 같은 트랜잭션에 발행할 일을 기록해 이 구간 자체를 없애는 방향이다.

## 3b 이후: 사례 A 복구

3b 대사(commerce-batch)와 재발행 명령 consumer(commerce-api)로 사례 A를 찾아 다시 흘려보낸 기록이다. 위 3a 기록은 그대로 둔다.

### 재현 조건

- 사용한 커밋: `f03f52b`. batch `OrderEventReconcileJobTest.caseAMissingConfirmationIsRequested`, api `OrderEventRepublishIntegrationTest.caseAIsRecoveredByRepublishRequest`를 쓴다. 대사·재발행 구현은 `e6609dd`·`7d39143`이다.
- 실행:
  - `./gradlew :apps:commerce-batch:test --tests '*OrderEventReconcileJobTest' -i`
  - `./gradlew :apps:commerce-api:test --tests '*OrderEventRepublishIntegrationTest' -i`
  - 두 명령 모두 `[실험④ 3b 사례 A 복구·…]` 줄을 확인한다.
- 두 모듈의 테스트는 각자 MySQL·Kafka 컨테이너를 띄운다. 그래서 한 실행 안에서 batch가 보낸 명령을 api가 그대로 받지는 않는다. 둘을 잇는 것은 공유 계약 fixture `republish-order-confirmed-v1.json`이다. batch 직렬화는 fixture와 같고, api 파서는 fixture를 주문 42 명령으로 읽는다. api 테스트는 batch가 만드는 것과 같은 모양의 명령을 topic에 직접 넣는다.
- batch: 사례 A와 같은 상태의 주문을 둔다. 주문 1, `CONFIRMED`, `confirmed_at = 2026-10-29T01:00:00Z`, `processed_order_confirmation`에 없음. Clock `2026-10-29T01:10:00Z`에서 Job을 한 번 실행한다(유예 5분·겹침 1분).
- api: 3a 사례 A와 같은 방식으로 상태를 만든다. 고정 Clock `2026-10-29T01:00:00Z`, 첫 상품×2·둘째 상품×1로 기존 주문 API → 테스트 PG(SUCCESS) → 결과 반영 경로를 거친다. commit 뒤 send 직전에서 멈춘다. 그다음 그 주문의 재발행 명령을 명령 topic에 넣는다.

### 결과

| 항목 | 값 |
| --- | --- |
| orderId | 1 |
| eventId | `order-confirmed:v1:1` |
| batch가 찾은 누락 | 주문 1 하나. 명령 record (topic=`order-event-republish-requests-it-…`, partition=0, offset=1), key=`1` |
| batch 명령 값 | `{"commandType":"RepublishOrderConfirmed","schemaVersion":1,"orderId":1,"requestedAt":"2026-10-29T01:10:00Z"}` |
| batch 워터마크 | 실행 뒤 (`2026-10-29T01:05:00Z`, 최대 주문 ID) |
| api 명령 | (topic=`order-event-republish-requests-it-…`, partition=0, offset=0) → 처리 후 group committed offset 1 |
| api가 재발행한 OrderConfirmed | (topic=`order-events-it-…`, partition=0, offset=0), key=`1`. 이 topic에서 이 eventId의 첫 record다 |
| 재발행 payload | `{"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:1","orderId":1,"confirmedAt":"2026-10-29T01:00:00Z","items":[{"productId":1,"quantity":2},{"productId":2,"quantity":1}]}` |
| 처음 발행됐어야 할 payload와 비교 | send 직전에 멈춘 payload를 직렬화한 값과 정규화해서 같고 key도 같다 |

batch 명령 offset이 1인 것은 같은 테스트 topic에 앞선 테스트의 명령이 있었기 때문이다. topic 이름 접미사는 실행마다 바뀐다.

### 소비 쪽까지의 연결

- 재발행 record의 모양은 3a 공유 계약 fixture와 같다. streamer `OrderConfirmedContractTest`가 이 모양을 구매 활동 2행으로 처리한다.
- 이미 소비된 주문이 다시 재발행되는 경우는 streamer의 기존 멱등 테스트가 덮는다.
  - `PurchaseActivityHandlerTest.sameEventTwiceIsNoOp`: 같은 eventId가 다른 offset으로 다시 오면 `ALREADY_PROCESSED`이고 구매 활동은 그대로다.
  - `PurchaseActivityRedeliveryTest`: 같은 record가 재전달돼도 no-op이다.
- 대사의 누락 정의는 `processed_order_confirmation`에 없는 주문이다. 따라서 이미 소비된 주문은 보통 명령 대상이 아니다. 다만 겹침 구간이나 send 실패 뒤 재실행에서는 같은 명령이 다시 나갈 수 있다.

### 남는 한계

- 사례 A 상태가 생긴 뒤 다음 대사 실행까지는 구매 활동에 빠져 있다. 지연은 최대 대사 주기 + 유예 5분이다.
- 대사는 확정 시각 구간만 본다. 워터마크보다 겹침 1분 넘게 늦게 commit된 확정은 찾지 못한다. 과거 구간 재훑기는 범위 밖이다.

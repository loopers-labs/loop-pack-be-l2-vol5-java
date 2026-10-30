# 주문 확정 이벤트와 구매 활동 설계 (W6)

> **주문 확정은 "일어난 사실"로 Kafka에 남기고, 구매 활동은 그 사실을 받아 한 번만 저장한다. 메시지를 받았다는 것과 업무를 끝냈다는 것을 구분한다.**<br>
즉, Kafka 기반 이벤트 전달에서 전달 보장(at-least-once)과 DB 효과의 멱등성을 다룬다.

PG로 확정된 주문을 `OrderConfirmed` 이벤트로 `commerce-streamer`에 전달해 상품별 구매 활동으로 저장한다. 구매 활동은 9주차 주간 랭킹의 입력이다. 이번 주에는 랭킹 API와 Outbox를 만들지 않는다. 구현 상세(이벤트 계약, 검사 순서, 설정값, 실험 절차, 테스트)는 [AI용 상세](order-event-design.ai.md)에 있다.

## 왜 이벤트인가

| | 이벤트 `OrderConfirmed` | 커맨드 `RecordPurchaseActivity` |
|---|---|---|
| 의미 | 주문이 확정됐다는 **지나간 사실** | 구매 활동을 기록하라는 **요청** |
| 발행자가 아는 것 | 사실뿐. 누가 듣는지 모른다 | 수행할 대상과 방법 |
| 소비 실패의 해석 | 확정은 취소되지 않는다. 소비자가 다시 처리한다 | 요청이 실패했으므로 호출자가 책임진다 |
| 후속 기능이 늘면 | 소비자만 추가한다 | 호출하는 주문 코드가 늘어난다 |

- 주문 확정이 구매 활동·랭킹·알림을 직접 부르면, 기능이 늘 때마다 주문 코드가 바뀌고 후속 장애(느린 저장 등)가 이미 성공한 결제를 실패로 만든다.
- 이벤트는 결합과 실패를 없애지 않는다. **전달 지연·중복·누락을 다뤄야 하는 경계로 옮길 뿐이다.** 아래 결정은 모두 이 경계를 다룬다.

## 이번 과제의 핵심 주제

| 주제 | 이번 설계의 답 |
|---|---|
| 발행 경계 | commit 전 발행은 거짓 이벤트, commit 뒤 발행은 누락 구간. **commit 뒤 직접 발행**하고 누락 구간을 실험으로 남긴다 |
| 순서와 분산 | key는 orderId. 순서는 partition 안에서만 보장된다. consumer가 partition 수보다 많으면 놀고 있는 consumer가 생긴다 |
| 읽은 위치와 완료한 위치 | position, committed offset, DB 효과는 서로 다르다. **DB commit 뒤에 ack** 한다(최소 한 번) |
| 중복과 충돌 | 같은 사실의 재전달은 정상 no-op, 같은 ID의 다른 사실은 충돌. DB 유일 제약과 한 트랜잭션으로 지킨다 |
| 실패 정책 | 실패 record는 건너뛰지 않는다. **컨테이너를 멈추고 원인을 드러낸 뒤** 고치고 재시작한다 |

## 핵심 결정

| 결정 | 이유 |
|---|---|
| `PaymentStatusUpdater`가 최초 확정일 때만 payload를 만들어 결과로 돌려주고, `PaymentResultLookupFacade`가 **트랜잭션이 끝난 뒤 명시적으로** 발행 포트를 부른다 | 최초 확정은 `succeed` 한 곳뿐이라 callback·reconcile 어느 쪽이 먼저 와도 같다. `writeTransaction.execute`가 반환되면 commit이 끝났고, rollback이면 예외로 발행 줄에 닿지 않는다. 발행 위치가 Facade 코드에 한 줄로 보여 commit~send 구간을 읽고 실험하기 쉽다. OSIV가 꺼져 있어 commit 뒤에는 LAZY 품목을 못 읽으므로 payload는 트랜잭션 안에서 만든다. 비용은 잠금 중 품목 조회 1회 |
| `eventId`는 `order-confirmed:v1:{orderId}`로 유도하고 key는 orderId 문자열 | 한 주문의 확정은 한 번뿐이라 따로 저장하지 않아도 재발행·재전달에서 같다 |
| `Order`에 `confirmedAt`을 추가하고 `Clock`으로 최초 전이 때 한 번만 설정 | payload 시각과 DB 사실을 맞추고, 중복 callback이 바꾸지 못하게 한다 |
| send가 실패해도 주문을 되돌리거나 PG를 다시 부르지 않고, 실패를 로그로 드러낸다 | 확정은 이미 일어난 사실이다. 누락은 다음 주 Outbox로 해결한다 |
| 완료 기록(`processed_order_confirmation`)과 모든 품목의 활동(`product_purchase_activity`)을 한 DB 트랜잭션에 저장한다 | 완료 기록만 먼저 있으면 업무가 누락되고, 활동만 먼저 있으면 중복된다 |
| 중복 판단은 `eventId`와 `orderId`의 유일 제약, 활동은 `(orderId, productId)` 유일 제약으로 한다 | 조회만으로는 동시 소비를 막지 못한다. 같은 주문을 다른 eventId로 보내도 더해지지 않는다 |
| 유일 제약 위반이 나면 트랜잭션을 rollback하고, 그 밖에서 저장된 사실과 비교한다. 같으면 no-op, 다르면 충돌로 중단 | 실패한 세션은 쓸 수 없다. 위반은 상대 트랜잭션이 commit된 뒤에만 나고 행은 추가만 되므로 별도 트랜잭션이 필요 없다. 다른 오류를 "중복"으로 바꾸지 않는다 |
| 이벤트용 record listener factory를 새로 만든다(비배치, 수동 ack, 중단형 error handler) | 기존 배치 factory(3000건 poll, 1MiB fetch)는 record별 DB 경계와 ack를 보기 어렵다. 기존 것은 바꾸지 않는다 |

## Kafka 메시지 전달 흐름

```
[commerce-api]  TX B: PG SUCCESS 반영
  PaymentStatusUpdater.succeed
    ├ 시도 SUCCEEDED, 주문 CONFIRMED + confirmedAt
    └ 결과에 확정 payload를 담는다         ← 최초 확정일 때만, payload는 여기서 만든다
  ── DB commit ──────────────────────────────  (rollback이면 예외, 아래 줄에 닿지 않음)
  PaymentResultLookupFacade                    ← writeTransaction.execute 반환 뒤
    └ 확정 payload가 있으면 OrderEventPublisher.publish(포트) 호출
    → OrderEventKafkaPublisher
    → Producer: KafkaTemplate.send(key=orderId)
        └ 결과 콜백: 성공 → topic·partition·offset 로그
                     실패 → 오류 로그 (주문은 그대로)

[Broker]  topic order-events
          partition 0: [..][n][n+1]     partition 1: [..]
          group purchase-activity 의 committed offset

[commerce-streamer]
  ListenerContainer (record factory, auto commit off, MANUAL_IMMEDIATE)
    └ consumer.poll
       └ @KafkaListener  OrderConfirmedConsumer
          1. 파싱·검증
          2. PurchaseActivityFacade  ── DB TX (완료 기록 + 품목 활동) commit
          3. ack.acknowledge()       ── offset n+1 commit
    └ 예외가 나면: ack 없음 → error handler가 컨테이너 중단
                    → 고친 뒤 재시작하면 committed offset n부터 다시 전달
```

| 구성 요소 | 하는 일 | 이번 설정 |
|---|---|---|
| Producer | record를 broker에 보내고 결과를 알려 준다 | `acks=all`, 멱등 활성, key는 orderId |
| Broker / topic / partition | record를 partition 로그에 남긴다. 읽어도 지워지지 않는다 | `order-events`, 실험 topic은 partition 2개 |
| Consumer group | partition을 member에게 나눠 준다. 위치(offset)는 group별로 관리한다 | `purchase-activity` |
| Listener container | poll하고 listener를 호출하며 ack·commit과 오류 처리를 맡는다 | record 단위, 동시성 1, `MANUAL_IMMEDIATE`, auto commit false |
| Listener | 파싱하고 업무 handler를 부른 뒤 성공했을 때만 ack한다 | `OrderConfirmedConsumer` |
| Error handler | 예외 뒤 offset을 어떻게 할지 정한다 | `CommonContainerStoppingErrorHandler`: 건너뛰지 않고 중단 |


## 관찰한 전달 결과

단일 broker에서 두 실험이 실제로 관찰한 값이다. 일이 어떻게 나뉘는가는 `PartitionDistributionTest`, 읽은 위치와 끝낸 위치의 차이는 `OffsetRedeliveryTest`가 본다.

**같은 group의 consumer를 늘렸을 때 (partition 2개)**

| consumer 수 | member-1 | member-2 | member-3 |
|---|---|---|---|
| 1 | partition 0, 1 | - | - |
| 2 | partition 0 | partition 1 | - |
| 3 | partition 0 | partition 1 | 배정 없음 (받은 record도 없음) |

consumer를 3개로 늘려도 일하는 member는 partition 수인 2개다. 같은 key로 5번 보낸 record는 매번 같은 partition으로 갔다(key `42`, `43` 모두 partition 0이었고, 서로 다른 key가 다른 partition으로 간다는 증거는 아니다).

**DB에는 반영했지만 offset을 commit하지 못한 채 끝난 consumer (target의 offset n=2)**

| 시점 | committed offset | 구매 활동 |
|---|---|---|
| 첫 consumer가 DB에 반영한 직후 | 2 (n) | 2행 |
| 첫 consumer 종료 뒤 | 2 (n) | 2행 |
| 새 consumer가 같은 record(같은 partition, offset 2)를 다시 받아 no-op 처리하고 commit한 뒤 | 3 (n+1) | 2행 |

전달은 2번이었지만 구매 활동은 한 번만 남았다.

## 누락 구간 관찰 (commit 뒤, 전송 전)

`OrderConfirmedPublicationGapTest`가 W6의 직접 발행 경로에서 두 사례를 재현한다. 값은 이번 실행의 관찰값이며, W7에서 다시 실행하면 주문·거래 ID는 새로 생긴다.

| | 사례 A (미발행) | 사례 B (정상) |
|---|---|---|
| 역할 | commit 뒤 전송 호출 직전의 hook에서 전송을 건너뜀 | 같은 경로에서 전송이 진행됨 |
| orderId / providerOrderId | 1 / `LP-ORD-0000000001` | 2 / `LP-ORD-0000000002` |
| transactionKey | `TEST-TX-0001` | `TEST-TX-0002` |
| eventId | `order-confirmed:v1:1` | `order-confirmed:v1:2` |
| confirmedAt (고정 시계) | `2026-09-07T01:00:00Z` | `2026-09-07T01:00:00Z` |
| items | 러닝화×2, 양말×1 | 러닝화×1 |
| 주문 상태 | CONFIRMED (재고 차감 유지) | CONFIRMED |
| broker의 이 eventId record | **0건** | 1건 |
| 구매 활동 | 소비할 record가 없어 생길 수 없음 | streamer `OrderConfirmedConsumerTest`가 같은 계약의 record를 소비해 저장함을 확인 |

- hook 시점(commit 뒤, send 전)에 DB를 새로 읽으면 이미 `CONFIRMED`이고 재고도 차감돼 있다(`CONFIRMED/shoes=7/socks=9`, 두 주문의 차감 합). 그래도 broker에는 A의 이벤트가 없다. DB와 broker가 원자적으로 연결되지 않았다는 관찰이다.
- 실행: `./gradlew :apps:commerce-api:test --tests '*OrderConfirmedPublicationGapTest'`. W6 revision은 이 테스트가 처음 추가된 커밋이다(`git log --diff-filter=A --format=%h -1 -- apps/commerce-api/src/test/java/com/loopers/application/payment/OrderConfirmedPublicationGapTest.java`).
- hook은 전송 호출을 건너뛴 것이지 프로세스를 강제 종료한 것이 아니다. 두 앱은 서로 의존하지 않아, A의 "구매 활동 없음"은 DB 관찰이 아니라 "record가 없다"는 사실에서 설명하고, api에서 streamer까지를 한 번의 실행으로 잇지는 않는다.

## 알려진 한계

- DB commit과 broker 기록이 원자적이지 않아 ② 구간이 남는다. 다음 주 Outbox로 개선한다.
- 단일 broker라 `acks=all`의 복제·ISR 장애 보장은 확인하지 못한다.
- 중단형 error handler라 충돌 record 하나가 그 partition의 소비를 멈춘다. 대신 업무 사실이 조용히 사라지지 않는다.
- 확정 경로가 하나 더 생기면 그 Facade도 발행을 직접 불러야 한다. 빠뜨리지 않도록 callback과 reconcile 경로 각각에 "실제 확정 → 발행" 테스트를 둔다.
- 이전 POINT 확정 주문은 이번 변경으로 소급 발행하지 않는다.
- 통제된 consumer 종료로 재전달을 관찰하며, OS 강제 종료·broker 장애·네트워크 분할을 시험한 것은 아니다.

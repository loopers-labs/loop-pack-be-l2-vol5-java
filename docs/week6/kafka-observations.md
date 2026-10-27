# W6 관찰 기록 — 주문 확정 이벤트·구매 활동

설계: [design.md 6장](../week2/design.md), DR-39~45.
이 문서의 값은 2026-10-27, `volume-6` 작업 트리(base `1280c6e`)에서 아래 테스트를 실행해 로그(`[W6 관찰: ...]`)로 얻은 것이다.
실행 환경: Testcontainers 단일 broker `apache/kafka:3.8.1`(KRaft, replica 1), `mysql:8.0`, JVM `user.timezone=Asia/Seoul`. 카드 값·비밀정보는 기록하지 않는다.

```bash
# Docker 필요. Testcontainers 는 실행이 끝나면 컨테이너를 정리한다.
./gradlew :apps:commerce-api:test --tests '*OrderConfirmedPublicationTest'
./gradlew :apps:commerce-streamer:test --tests '*KafkaDeliveryTest' --tests '*OrderConfirmedConsumerTest'
```

ID 는 실행마다 새로 생긴 값이다. 테스트마다 DB 를 비우므로 두 사례 모두 orderId 1 이다. 다음 주 재현에서는 새 실행의 실제 ID 를 다시 얻는다 (숫자를 맞추지 않는다).

## 1. W7 로 넘기는 두 사례 (commerce-api, `OrderConfirmedPublicationTest`)

공통 준비 (재현 코드에 고정):
- 고정 clock `2026-09-07T01:00:00.123456789Z` → 확정 시각은 마이크로초로 잘려 `2026-09-07T01:00:00.123456Z`
- 품목: 상품 A(id 1) 2개·1,000원, 상품 B(id 2) 1개·500원, 각 재고 5
- 경로: 기존 주문 생성(`OrderFacade.createOrder`) → 확정(`confirmOrder`) → `FakePaymentGateway` 동기 SUCCESS(거래 키 `tx-{orderId}`) → 실제 `applyResult` → commit → AFTER_COMMIT 발행
- topic: `order-events-publication-test` (partition 3)

| 사례 | 역할 | orderId | providerOrderId | transactionKey | eventId | confirmedAt | items | 주문 | 재고(A/B) | broker | 구매 활동 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 미발행 (`confirmedOrderRemains_withoutBrokerRecord_whenStoppedBeforeSend`) | commit 뒤 send 직전 hook 에서 예외 주입 | 1 | ORDER-1 | tx-1 | order-confirmed:v1:1 | 2026-09-07T01:00:00.123456Z | 1×2, 2×1 | CONFIRMED | 3 / 4 (RESERVE 원장) | 해당 record 없음. log end offset 전후 동일 `{p0=1, p1=0, p2=0}` | 없음 (record 가 없다) |
| 정상 발행 (`publishesOrderConfirmed_matchingStoredOrder`) | 실제 broker 까지 | 1 | ORDER-1 | tx-1 | order-confirmed:v1:1 | 2026-09-07T01:00:00.123456Z | 1×2, 2×1 | CONFIRMED | 3 / 4 | `order-events-publication-test-0@1`, key `"1"`, payload = 저장된 주문 | 소비는 streamer 테스트·로컬 실행에서 확인 (아래 3·5절) |

- 미발행 사례에서 확정 API 응답은 CONFIRMED 로 정상이다. 발행 실패가 주문 결과를 바꾸지 않는다. 이것은 **DB 와 broker 가 원자적이지 않다**는 증거이며, 실패 주입이지 OS 강제 종료가 아니다.
- W7 은 같은 재현 코드(이 테스트의 준비 함수와 W6 직접 발행 경로)로 새 격리 실행을 만들고, Outbox 로 이 누락이 복구되는지 본다. 행을 지워 누락을 꾸미지 않는다.

## 2. 배정 (`KafkaDeliveryTest.assignmentWithinGroup`)

partition 2개 topic, 같은 group. 각 member 는 자기 스레드에서 poll 하며 실제로 group 에 참여하고, 배정은 rebalance listener 가 알려 준 값이다.

| 같은 group 의 consumer | 배정 |
|---|---|
| 1개 | c1 = [0, 1] |
| 2개 | c1 = [0], c2 = [1] |
| 3개 | c1 = [0], c2 = [1], c3 = [] (배정 없음) |

2개일 때 partition 0·1 에 명시적으로 보낸 record 를 각각 담당 member 만 읽었다.

## 3. key → partition (`KafkaDeliveryTest.sameKeyGoesToSamePartition`)

partition 2개, partition 은 producer 가 고르게 하고 key 별로 3회씩 보냈다.

| key | 3회의 partition |
|---|---|
| 42 | 0, 0, 0 |
| 43 | 0, 0, 0 |
| 44 | 1, 1, 1 |
| 45 | 0, 0, 0 |

같은 key 는 같은 partition 이었다. 서로 다른 key 가 다른 partition 으로 간다는 보장은 없다 (42·43·45 가 같은 partition). partition 수를 바꾸면 매핑이 달라질 수 있다.

## 4. DB commit 뒤 offset 미커밋 재전달 (`KafkaDeliveryTest.redeliveryAfterDbCommitWithoutOffsetCommit`)

실제 OrderConfirmed JSON(주문 70: 상품 7×2, 8×1) 한 건, 실제 parser·`PurchaseActivityFacade`, 같은 broker·topic·group·DB.

| 순서 | 행동 | 관찰 |
|---|---|---|
| 준비 | 격리 topic 에 target 발행 | target = `w6-redelivery-…-0@0` (n = 0, send metadata) |
| 시작 위치 | 첫 consumer 구독·배정 뒤 n commit, n 에서 읽기 (테스트 준비) | committed 0 |
| 첫 처리 | poll → handler DB commit | RECORDED, 활동 2행, position 1 |
| 종료 | n+1 commit 요청 없이 `close` (auto commit false, 대기 commit·종료 callback 없음) | committed 0 |
| 재시작 | 새 consumer, 같은 group, seek·reset 없음 | 같은 `…-0@0` 재전달 |
| 두 번째 처리 | handler no-op 뒤 n+1 commit | ALREADY_PROCESSED, committed 1 |

결과: 전달 2회, 같은 위치, committed offset `0 → 0 → 1`, 활동 `[7×2, 8×1]` 불변, 완료 기록 1건.

## 5. 실제 listener 의 실패·ack (`OrderConfirmedConsumerTest`)

| 단계 | 관찰 |
|---|---|
| 앞선 record 처리 | committed 2 |
| target 발행 (주문 50, 두 번째 품목 저장에서 DB 오류 주입) | target = `order-events-consumer-test-0@2` (n = 2) |
| 재시도 | 1초 간격 2회 재시도, 총 3회 시도, 매번 완료 기록·앞선 품목까지 rollback |
| 소진 | 컨테이너 중단. committed 2 (n 그대로), 주문 50 활동·완료 기록 없음 |
| 오류 제거 후 같은 group 으로 재시작 | 같은 record 재전달 → 저장 → ack. committed 3 (n+1), 총 시도 4회, 활동 2행 |

계약 위반(같은 eventId 의 다른 수량, `OrderConfirmedConsumerContractViolationTest`)은 재시도 없이 1회 시도 뒤 중단했고, committed offset 은 실패 record 에 머물렀다.

## 6. 로컬 전체 실행 (api → Kafka → streamer)

미실행. `docker/infra-compose.yml`(MySQL·Kafka) 위에 두 앱을 띄우고 실제 PG 시뮬레이터(`localhost:8010`)로 확정해야 하는데, 시뮬레이터 실행 방법을 확인하지 못했다.

## 7. 남은 빈틈과 한계

- **commit → send 누락 구간**: 확정 commit 과 send 사이에서 멈추거나 send 가 최종 실패하면 이벤트가 빠진다. 지금은 로그(`OrderConfirmed 발행 실패`)와 `order.confirmed.publish{result=failure}` 로만 드러난다. W7 Outbox 로 개선한다.
- **단일 broker**: `acks=all`·idempotence 를 켰지만 replica 1 이라 복제·leader 장애 내구성은 확인하지 못했다.
- **통제된 종료만**: consumer `close`·컨테이너 stop 으로 관찰했다. OS 강제 종료·broker 장애·네트워크 분할은 시험하지 않았다.

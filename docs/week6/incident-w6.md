# W6 실험 결과와 사건 기록

> `docs/week6/plan.md` D-8의 실험(E-1 ~ E-6)을 실제 broker로 돌린 결과다. 값은 2026-10-29 새벽 `volume-6-claude` 실행의 관찰값이다.
> 다시 돌리면 ID·offset은 달라진다 — 숫자를 맞추는 것이 아니라 같은 관계(누락·재전달·n → n → n+1)가 다시 나오는지 본다.
>
> 공통 환경 — 테스트는 Testcontainers broker 3대(KRaft, `apache/kafka:3.8.1`, RF 3, min.insync 2)와 compose MySQL의 `loopers_test` 스키마를 쓴다.
> 컨테이너는 Testcontainers(Ryuk)가 테스트 JVM 종료 때 지운다. 임시 DB를 남겨 두지 않는다 — 다음 주에 넘기는 것은 재현 코드와 아래 관찰 사실이다.

## E-4 · 직접 after-commit 발행의 누락 구간 (C-8, C-9)

**재현 코드** — `apps/commerce-api/src/test/java/com/loopers/payment/scenario/S13PublishGapScenarioTest.java`
**실행** — `./gradlew :apps:commerce-api:test --tests '*S13PublishGapScenarioTest'` (W6 커밋 `e776016f` 이후)

- 실제 주문 API → 확정 요청(202) → 로컬 PG fixture가 수락·승인 → **PG callback** → 결과 적용 경계(`PaymentResultApplier`)로 두 주문을 확정한다.
- 첫 주문만 commit 뒤 send 직전에 멈춘다 — `OutboxPublishUseCase.publish`를 한 번 건너뛰는 **통제된 hook**이다. OS 강제 종료가 아니다. 결과(send 콜백 없음)는 프로세스 종료와 같다.
- 고정한 품목: 에어맥스(상품 1) 재고 5 · 주문 A 2개 · 주문 B 1개.

| 사례 | orderId | providerOrderId | transactionKey | eventId | confirmedAt | items | 주문 | 예약 | 발행 기록 | broker |
|---|---|---|---|---|---|---|---|---|---|---|
| 미발행 | 1 | LP-ORD-0000000001 | 20261029:TR:0241b32 | a6669a2d-1880-4ad5-8bf4-fc3c447396f5 | 2026-10-29T00:44:05.107028+09:00 | [{1, 2}] | CONFIRMED | CONSUMED | published_at **null**, last_error null | **record 없음** |
| 정상 발행 | 2 | LP-ORD-0000000002 | 20261029:TR:22e1243 | 6671da9f-dce3-4806-b2cf-5f055ffe867a | 2026-10-29T00:44:05.292186+09:00 | [{1, 1}] | CONFIRMED | CONSUMED | published_at 2026-10-28T15:44:06Z | partition 2 · offset 0 |

- 누락 조회(`published_at IS NULL AND created_at < 기준`)는 주문 1만 찾는다. PG 생성 호출은 2번 그대로다 — 주문을 되돌리거나 PG를 다시 부르지 않았다.
- 주문 1의 `last_error`가 null이다 — 실패가 아니라 **send 콜백조차 오지 않았다**. 실패만 기록했다면(ADR-W6-03 (i)) 이 주문은 흔적이 없었다.
- 전송 실패 주입(broker 응답 없음)도 같은 테스트 클래스에 있다: 주문 CONFIRMED·예약 CONSUMED·결제 SUCCESS, `last_error = org.apache.kafka.common.errors.TimeoutException: broker 응답 없음 (주입)`, published_at null.
- 이것이 DB와 broker가 원자적으로 묶이지 않았다는 증거다. **W7 Outbox relay의 출발점** — relay는 published_at null인 행을 같은 eventId로 다시 보낸다.
- 정상 발행 사례가 streamer에서 구매 활동이 되는 쪽은 자동 테스트가 경계(record)에서 나뉜다(D-9). streamer 쪽은 같은 모양의 견본으로 `OrderConfirmedListenerIntegrationTest`가 확인한다. 두 앱을 이어 한 번에 보는 실행은 아래 "전체 흐름 런북"으로 남긴다.

## E-1 · partition 배정 (C-4)

**재현 코드** — `apps/commerce-streamer/src/test/java/com/loopers/kafka/PartitionAssignmentExperimentTest.java`

partition 2개인 실험 topic, 같은 group. 각 member는 자기 스레드에서 poll하고 rebalance listener가 받은 배정을 기록한다.

| 같은 group의 consumer | 배정 |
|---|---|
| 1개 | m1 = [0, 1] |
| 2개 | m1 = [0], m2 = [1] |
| 3개 | m1 = [0], m2 = [1], **m3 = []** |

- partition 0·1에 명시적으로 보낸 실험 record는 각 partition의 담당자만 읽었다. 배정 없는 m3는 0건.
- key 매핑: `order-42`를 세 번 보내면 partition [0, 0, 0]. 다른 key는 order-1 = 0, order-2 = 1, order-3 = 1, order-4 = 0 — 다른 key가 다른 partition으로 간다는 보장은 없다.
- 그래서 consumer를 partition 수(order-events는 3)보다 늘려도 처리 담당자는 늘지 않는다.

## E-2 · DB commit 뒤 offset 미커밋 재전달 (C-5)

**재현 코드** — `apps/commerce-streamer/src/test/java/com/loopers/kafka/RedeliveryAfterDbCommitTest.java`

| 순서 | 관찰 |
|---|---|
| 준비 | 실험 topic(partition 1)에 target 발행 → offset n = 0 |
| 첫 consumer | 배정 뒤 n을 commit, n에서 읽고 실제 handler로 DB commit → 활동 2행. n+1은 요청하지 않고 close (auto commit false) |
| 재시작 | 같은 group의 새 consumer, seek·offset reset 없음(`auto.offset.reset=none`) |
| 두 번째 처리 | 같은 topic-0@0 재전달 → handler no-op(DUPLICATE) → n+1 commit |

- 전달 2회, 같은 위치(`topic-0@0`, `topic-0@0`), **committed offset 0 → 0 → 1**, 활동 2행 그대로.
- 첫 실험은 "DB 효과 성공 뒤 offset 공백"을 본다. 실제 listener의 실패·ack 정책은 E-3이 따로 본다.

## E-3 · 실제 listener의 DB 실패·재시도·DLT·재생 (C-7)

**재현 코드** — `apps/commerce-streamer/src/test/java/com/loopers/purchase/adapter/in/kafka/OrderConfirmedListenerIntegrationTest.java`

- `saveAll`에 DB 장애를 넣으면 같은 자리에서 **1 + 3번** 시도(1s·2s·4s backoff) → `order-events.DLT`에 원문 그대로 기록(헤더: 원래 partition·offset·예외 메시지) → 원래 offset **n+1 commit** → 활동·완료 기록 0행.
- 오류를 없앤 뒤 DLT record의 key·value를 그대로 `order-events`에 다시 넣으면(테스트 코드가 재생) 1번 저장되고, 한 번 더 넣으면 no-op이다.
- 충돌(같은 eventId · 다른 수량)과 계약 위반(수량 0 · 수량 1.9)도 같은 1 + 3번 시도한 뒤 DLT로 간다 (ADR-W6-06 변경, 10-29 학습자 결정). 시도 횟수는 파싱 호출 수로 단언한다.
- 재시도 도중에는 committed offset이 n 이하로 남는다 — 처리 전에 ack하도록 코드를 바꾸면 이 테스트가 실패한다(10-29 확인).

구현 중 드러난 사실 — 설계에 반영했다 (plan.md 변경 이력).

| 관찰 | 원인 | 조치 |
|---|---|---|
| DLT 발행이 86번 연속 실패하고 그 partition의 뒤 record가 처리되지 않았다 | Spring Kafka 3.3의 기본 DLT 이름은 `원래topic-dlt`인데 topic은 `.DLT`로 만들었다. recover가 실패하면 offset을 commit하지 않고 같은 record를 다시 시도한다 — **유실은 없지만 partition이 멈춘다** | DLT 이름을 resolver로 명시 |
| recover가 성공했는데 committed offset이 null(n)에 머물렀다 | MANUAL_IMMEDIATE에서는 `commitRecovered=true`일 때만 recover한 offset을 바로 commit한다(기본 false). false면 같은 partition의 다음 record가 ack될 때 함께 넘어간다 | `setCommitRecovered(true)`. 과제가 경고한 대로 "commitRecovered=false면 모든 commit이 막힌다"는 아니다 |
| 재시도가 4번이 아니라 6번 일어났다 | 컨텍스트가 막 떠서 consumer 3개가 group에 들어오는 중(rebalance)에 실패가 났고, 재시도 횟수 추적이 다시 시작됐다 | 테스트는 배정이 안정된 뒤 시작. 운영에서도 rebalance 중에는 정책 횟수보다 더 시도될 수 있다 |

## E-5 · broker 장애와 복제 (ADR-W6-04)

**재현 코드** — `apps/commerce-api/src/test/java/com/loopers/kafka/BrokerFailureReplicationTest.java` (이 테스트만의 클러스터를 따로 띄운다)

| 상태 | topic min.insync | 결과 |
|---|---|---|
| 3대 정상 | 2 | 쓰기 성공 |
| leader 1대 정상 종료 | 2 | 새 leader로 넘어가 쓰기 성공. 정지 전에 성공한 record도 남아 있다 |
| 1대 정지 | **3** | `NotEnoughReplicasException` — ISR 2 < 3 (min.insync의 동작) |
| 2대 정지 | 2 | `NotEnoughReplicasException` — ISR 1 < 2. 유실 대신 멈춘다 |

- 정상 종료(controlled shutdown)는 떠나기 전에 ISR에서 빠진다. 그래서 2대가 빠진 뒤 남은 leader의 ISR은 자기 하나이고, controller quorum(2/3)을 잃었어도 쓰기 거절은 남은 leader가 판단한다.
- 강제 종료(kill)는 ISR이 `replica.lag.time.max.ms` 뒤에 줄어 결과가 다를 수 있다 — 이번 실험이 증명하지 않은 것.
- 관찰: 방금 만든 topic에 바로 보내면 metadata가 퍼지기 전이라 `max.block.ms` 1s 안에 첫 send가 막힐 수 있다(`Topic not present in metadata after 1000 ms`). 앱의 topic은 기동 때 만들어지므로 평소 경로와는 다르다.

## E-7 · consumer 프로세스가 group을 떠날 때 — 정상 종료 vs kill -9 (10-30)

**재현 코드** — `docs/week6/rebalance/rebalance.py` (`--mode term` · `--mode kill`). compose broker 3대 · 실험 DB `loopers_w6` · 같은 streamer jar를 프로세스 두 개(S1 · S2)로 실행 → 같은 group에 member 6개, partition 3개.
1초에 1건 record(key = 주문 ID)를 넣다가 10초에 partition을 가진 프로세스를 끈다. 기본값 그대로: `session.timeout.ms` 45s · heartbeat 3s · eager(Range) rebalance.

| | 정상 종료 (SIGTERM) | kill -9 (SIGKILL) |
|---|---|---|
| 끈 프로세스가 가진 partition | S1 = [1] | S1 = [0] |
| 그 partition을 다른 프로세스가 받기까지 | **1.7s** (t=10.0 revoke → t=11.7 assign) | **43.3s** (t=10.0 kill → t=53.3 rebalance 시작, 53.6 assign) |
| 그동안 그 partition에 들어온 record | 1건, 1.7s 기다림 | 12건, 최대 **42.7s** 기다림 → 새 담당자가 1초 안에 몰아서 처리 |
| 유실 · 중복 처리 | 0 · 0 (40건) | 0 · 0 (70건) |
| 다른 partition | 남은 프로세스의 partition도 revoke → 다시 assign (eager) | 같음 — partition 1은 약 0.35s, partition 2는 약 0.02s 멈춤 |

- 정상 종료는 consumer가 close하며 group을 떠난다고 알린다(LeaveGroup) → coordinator가 바로 rebalance.
- kill -9는 아무도 알리지 않는다 → coordinator는 마지막 heartbeat 뒤 `session.timeout.ms`(45s)가 지나서야 member를 뺀다. 그 사이 partition 0은 **담당자는 있는데 아무도 읽지 않는** 상태였다. record는 broker에 그대로 있었고(유실 0) 순서대로 처리됐다.
- 중복 처리가 없었던 것은 record마다 처리 직후 commit했기 때문이다. 죽은 순간이 "DB 저장 뒤 · commit 전"이면 그 1건이 다시 오고 no-op이 된다 — 그 장면은 E-2가 결정적으로 재현한다.
- eager rebalance라 끄지 않은 프로세스의 partition도 잠깐 내려놓았다가 다시 받았다(로그의 revoked → assigned).
- 한계: 1회 실행. 정상 종료 실행은 console producer의 기본 `linger.ms`(1s) 때문에 평소 지연이 약 1s로 보인다 — kill 실행에서는 `linger.ms=0`으로 고쳐 평소 지연이 0.0 ~ 0.3s다.

## E-6 · `max.block.ms`와 callback 스레드 (수동, 아직 안 함)

키트와 절차는 `docs/week6/k6/README.md` 3절. 학습자가 직접 돌려 예측 → 측정 → 차이를 그 표에 채운다.
장면을 둘로 나눈다 — A: 운영 중 broker 전부 정지(producer가 metadata를 이미 가짐), B: broker 정지 상태에서 api 기동(metadata 없음).

## 전체 흐름 런북 (두 앱을 이어 한 번 실행)

자동 테스트는 broker record에서 나뉜다(D-9). 확정 → broker → streamer → 구매 활동을 한 번에 본다. 명령은 `docs/week6/k6/README.md` 0 ~ 2절.

1. compose(MySQL · Redis · broker 3대 · kafka-ui) → 실험 DB `loopers_w6`(W5 스키마 + W6 스키마 + example) → seed
2. `pg-sim.py`(auto, 8082) → api(8080) · streamer(8085)를 `config-base.yml`로 실행
3. 주문 → 확정 요청(202) → 가짜 PG가 1초 뒤 SUCCESS callback
4. 확인: `event_outbox`(published_at · partition · offset), order-events record, `product_purchase_activity` 행, group의 committed offset
5. 이미 소비된 record를 broker에 다시 넣어 no-op과 offset 진행을 본다

### 실행 결과 (2026-10-29 19:14, `volume-6-claude` `a06bdcd5` 이후 + 키트)

환경: compose broker 3대 · 실험 DB `loopers_w6` · `pg-sim.py`(auto) · api(8080, `max.block.ms` 1s) · streamer(8085). 키트 `docs/week6/k6/`.

| 사례 | orderId | providerOrderId | transactionKey | eventId | confirmedAt (payload) | items | 주문 | 예약 | 발행 기록 | broker | 소비 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 정상 확정 → 소비 | 2 | LP-ORD-0000000002 | 20261029:TR:950df9d | 0304edaa-252b-439c-abe8-93af8ba9bb9e | 2026-10-29T19:14:14.246363+09:00 | [{1, 1}, {3, 2}] | CONFIRMED | CONSUMED ×2 | published_at 19:14:15.508 (KST), partition 2 · offset 0 | order-events-2@0 | 완료 기록 1 · 활동 2행 (상품 1×1, 상품 3×2) |

- committed offset (group `commerce-streamer.product-purchase-activity`, order-events-2): 처음 `-`(없음) → 소비 뒤 **1** · LAG 0. 다른 partition은 record 없음.
- **이미 소비된 1건 (C-9)**: 같은 payload를 key `2`로 broker에 다시 넣었다(order-events-2@1, 사람이 재발행한 상황). streamer 로그 `같은 사실의 재전달 — 변경 없이 완료(no-op)`, 완료 기록 1 · 활동 2행 그대로, committed offset **1 → 2**.
- 소비 시각: 발행 기록 표시 19:14:15.508, 활동 저장 로그 19:14:15.813.
- 같은 실행에서 우연히 남은 사실: 첫 주문(orderId 1)은 가짜 PG의 버그(chunked 본문을 못 읽어 생성 400)로 결제 FAILED · 주문 PAYMENT_FAILED · 예약 RELEASED가 됐고 **발행 기록·record는 0건**이었다 — 실패 주문에서 거짓 이벤트가 없다(C-2와 같은 결과). 버그는 고쳤다.
- 정리: 실험 DB `loopers_w6`는 E-6에서 다시 쓰므로 남겨 두고, 매 실행 전 `seed.sql`로 비운다. 테스트 컨테이너는 Ryuk가 지운다.

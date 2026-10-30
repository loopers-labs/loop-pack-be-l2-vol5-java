# W6 실험 결과

실험은 Testcontainers 로 띄운 broker 하나(`confluentinc/cp-kafka:7.5.0`, Kafka 3.5)와 MySQL 8.0 에서 돌렸다. 각 실험의 topic · group 은 실행마다 새로 만든다(`exp-partition-<uuid>`). 값은 `./gradlew :apps:commerce-streamer:test --tests '*PartitionExperimentTest'` 의 출력이다.

## EXP-002 배정 — `PartitionExperimentTest.assignsPartitionsAcrossGroupMembers`

partition 2 개인 topic, 같은 group 에 consumer 를 하나씩 더하며 모두 poll 하고, 배정이 10 회 연속 같을 때를 안정으로 본다.

| 같은 group 의 consumer | broker 가 준 배정 |
|---|---|
| 1 개 | `[{-0, -1}]` |
| 2 개 | `[{-0}, {-1}]` |
| 3 개 | `[{-0}, {-1}, {}]` — 셋째는 배정 없음 |

- 같은 group 안에서 partition 하나는 consumer 하나에만 간다. 그래서 partition 수가 병렬의 상한이다(설계 4.4).
- 세 번의 재배정과 안정까지 약 16 초가 걸렸다. 대부분은 consumer 가 더해질 때마다 group 이 다시 합의하는 시간이다.

## EXP-003 key 매핑 — `PartitionExperimentTest.mapsSameKeyToSamePartition`

| 보낸 것 | send metadata |
|---|---|
| partition 0 명시 | `-0@0` |
| partition 1 명시 | `-1@0` |
| key `42` 첫째 | `-0@1` |
| key `42` 둘째 | `-0@2` |

- 명시한 두 partition 의 record 를 consumer 하나가 모두 읽었다.
- 같은 key 는 producer 의 partitioner 가 같은 partition 을 골랐고, offset 은 그 partition 안에서 이어진다. 같은 주문의 이벤트가 이 순서로 소비된다(설계 2.1).
- 서로 다른 key 가 다른 partition 으로 간다고는 단언하지 않는다. 해시가 겹치면 같은 partition 이다.

## EXP-005 재전달 — `RedeliveryExperimentTest.redeliversUncommittedRecordWithoutSecondEffect`

partition 1 개인 격리 topic 에 준비용 확정(주문 41)과 target 확정(주문 42)을 보내고, 실제 reader 와 handler(`PurchaseActivityHandler`)로 처리한다. 첫 consumer 는 준비용을 반영한 뒤 그 다음 위치를 commit 하고, target 을 DB 에 반영한 뒤 **commit 하지 않고 닫는다.** 둘째 consumer 는 seek · offset reset 없이 같은 group 으로 읽는다.

| 값 | 결과 |
|---|---|
| 준비용 offset | 0 |
| target offset | 1 |
| 첫 consumer 가 닫힐 때 committed | 1 — target 은 DB 에만 반영되고 offset 은 남지 않았다 |
| 둘째 consumer 가 받은 첫 record | offset 1(target). 준비용(0)은 다시 오지 않았다 |
| handler 결과 | `SAME_FACT` |
| 주문 42 의 활동 행 | 첫 반영 뒤와 같다 — 2 행, 행 ID · 수량 · `updated_at` 까지 같다 |
| 주문 42 의 처리 기록 | 1 |

- "DB commit 은 됐는데 offset commit 전에 죽는" 구간은 실제로 같은 record 를 한 번 더 준다. at-least-once 다.
- 그 재전달을 처리 기록의 `uk_receipt_event` 가 막아, 활동은 다시 쓰이지도(`updated_at` 불변) 더해지지도 않는다. 효과는 한 번이다.
- 준비용 record 가 다시 오지 않은 것으로, 둘째 consumer 가 committed offset 을 이어받았고 처음부터 다시 읽은 것이 아님을 확인했다.

## EXP-006 listener 실패 — `ListenerFailureExperimentTest.retriesDbFailureAndRecoversWithoutRestart`

실제 Spring listener 컨테이너를 격리 topic · group(`exp-listener-failure`)에 붙이고, `purchase_activity` 에 `CHECK (product_id <> 8)` 을 걸어 확정(주문 42: 상품 7 × 2, 8 × 1)의 **둘째 품목만** DB 가 거절하게 한다.

| 시점 | committed offset | 처리 기록 | 활동 | 컨테이너 |
|---|---|---|---|---|
| 보내기 전 | 없음 | 0 | 0 | 실행 |
| record offset 0 처리 → CHECK 위반 → 재시도 2 회 | 없음 — 그 record 를 지나지 않았다 | 0 | **0** — 먼저 들어간 상품 7 까지 rollback | **실행 중** |
| 제약을 걷어 냄 | 1 | 1 | 2 | 실행 중 — **재시작하지 않았다** |

- 처리 기록 insert 뒤 활동 둘째 행에서 실패했는데 처리 기록 · 첫 활동이 모두 남지 않았다. 한 TX 다(`ACT-003`).
- 실패 동안 error handler 는 그 record 를 처리됨으로 commit 하지 않고 1 초, 2 초 간격으로 다시 시도했다(`purchase.activity.retry` 2).
- 제약을 걷어 내자 **2.1 초 만에** 사람의 재시작 없이 같은 record 를 반영하고 offset 이 넘어갔다. 원인이 풀리면 스스로 이어간다.

## partition 하나만 기다림 — `OrderConfirmedListenerRetryTest.parksOnlyTheFailingPartition`

partition 2 개인 격리 topic 에, partition 0 으로 모르는 `schemaVersion`(2) 확정(주문 42)과 정상 확정(주문 44)을, partition 1 로 정상 확정(주문 43)을 보낸다.

| | 결과 |
|---|---|
| partition 0 | 주문 42 에서 다시 시도(2 회 이상). committed 없음 — offset 0 을 지나지 않았다. 뒤의 주문 44 도 반영되지 않았다 |
| partition 1 | 주문 43 반영 |
| 보류 · 컨테이너 | 보류 0 · 실행 중 |

- 모르는 버전은 보류하지 않고 기다린다. 소비자를 배포하면 그 record 부터 처리된다.
- consumer 스레드가 partition 마다 하나(concurrency 3)라, 한 partition 이 기다리는 동안 다른 partition 은 진행한다.

## EXP-009 일시 오류 회복 — `OrderConfirmedListenerTest.retriesLockWaitTimeout`

다른 TX 가 같은 `eventId` 의 처리 기록을 insert 한 채 4.5 초 동안 commit 하지 않는다. listener 의 insert 가 그 유일 키 잠금을 기다리다 3 초(`innodb_lock_wait_timeout`)에 MySQL 1205 로 실패한다.

| 값 | 결과 |
|---|---|
| record | `order-events-1@4` |
| 로그 | `SQL Error: 1205` → `일시 오류로 주문 이벤트를 PT1S 뒤 다시 처리한다: attempt=1` |
| 보낸 뒤 반영까지 | 4.2 초 — 3 초 대기 · 1 초 쉼 · 잠금이 풀린 뒤 두 번째 시도 |
| committed offset | 5 — 그 record 를 지났다 |
| 컨테이너 | 계속 실행 중 |
| 처리 기록 · 활동 | 1 · 2 |

## EXP-007 · EXP-008 발행 누락 — `docs/week6/gap/run.sh` → `docs/week6/gap/result.md`

두 앱과 PG fixture 를 프로세스로 띄우고 실제 주문 · 결제 API 로 세 주문을 확정한다. broker 는 `kcat`, committed offset 은 `kafka-consumer-groups.sh` 로 읽는다. 아래 값은 마지막 실행의 것이다(전문은 `result.md`).

- 주문 A: topic 의 `max.message.bytes=1` 로 broker 가 거절하게 한다
- 주문 B: 설정을 되돌리고 확정한다
- 주문 C: api 를 `linger.ms=60000` 으로 다시 띄워 확정한 뒤, record 가 producer 버퍼에 있는 동안 `kill -9` 로 api 를 죽인다

| | 주문 A (1) | 주문 B (2) | 주문 C (3) |
|---|---|---|---|
| 주문 · 결제 | `CONFIRMED` · `TEST-TX-0001` `SUCCESS` | `CONFIRMED` · `TEST-TX-0002` `SUCCESS` | `CONFIRMED` · `TEST-TX-0003` `SUCCESS` |
| 재고 | 코트 2 · 셔츠 1 소비 | 같음 | 같음 (100 → 94 · 97) |
| broker (kcat) | **record 없음** | partition 2 · offset 0 · header 4 개 | **record 없음** |
| committed offset | — | partition 2 → 1 (lag 0) | — |
| 처리 기록 · 활동 | **없음** | 처리 기록 1 · 활동 2 행 | **없음** |
| api 지표 | `order_event_publish_total{result="failure", cause="RecordTooLargeException"} 1` | `{result="success"} 1` | **지표 자체가 없다** — kill -9 직전에 읽었다 |
| api 로그 | `주문 이벤트를 보내지 못했다. eventId 로 다시 보내야 한다: eventId=order-confirmed:v1:1` | — | **발행 로그 없음** |

- **세 주문 모두 결제됐고 확정됐다. 그런데 A 와 C 는 구매 활동이 영영 생기지 않는다.** 확정 TX 는 commit 됐고 broker 에 record 가 없어 consumer 가 읽을 것이 없다. 이것이 commit 뒤 발행(AFTER_COMMIT)의 누락 구간이다.
- **주문 A 는 흔적이 남는 누락이다.** 실패 지표와 `eventId` 가 든 ERROR 로그가 남아 알림을 걸 수 있고 사람이 다시 보낼 수 있다.
- **주문 C 는 흔적이 없는 누락이다.** record 가 producer 버퍼에서 broker 로 가기 전에 프로세스가 죽어, `whenComplete` 가 불리지 않았다. 성공도 실패도 세지 않았고 로그도 없다. 알림을 걸 수 없고, 다시 보낼 단서(`eventId`)도 남지 않는다. 지표로는 이 누락을 막을 수도 찾을 수도 없다.
- 주문 C 의 `linger.ms=60000` 은 그 순간을 매번 같게 만드는 수단이다. 운영의 `linger.ms` 기본값(0 ~ 5 ms)에서도 commit 과 broker ack 사이에는 늘 이 창이 있다. 짧을 뿐 없어지지 않는다.
- 세 주문의 차이는 코드가 아니라 broker 상태와 프로세스 종료 시점뿐이다. W7 은 이 스크립트를 outbox 경로로 다시 돌려 주문 A 와 C 가 복구되는지 본다.

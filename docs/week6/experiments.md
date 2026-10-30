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
| broker (kcat) | **record 없음** | partition 2 · offset 0 · header 는 `traceparent` 하나 | **record 없음** |
| committed offset | — | partition 2 → 1 (lag 0) | — |
| 처리 기록 · 활동 | **없음** | 처리 기록 1 · 활동 2 행 | **없음** |
| api 지표 | `order_event_publish_total{result="failure", cause="RecordTooLargeException"} 1` | `{result="success"} 1` | **지표 자체가 없다** — kill -9 직전에 읽었다 |
| api 로그 | `주문 이벤트를 보내지 못했다. eventId 로 다시 보내야 한다: eventId=order-confirmed:v1:1` | — | **발행 로그 없음** |

- **세 주문 모두 결제됐고 확정됐다. 그런데 A 와 C 는 구매 활동이 영영 생기지 않는다.** 확정 TX 는 commit 됐고 broker 에 record 가 없어 consumer 가 읽을 것이 없다. 이것이 commit 뒤 발행(AFTER_COMMIT)의 누락 구간이다.
- **주문 A 는 흔적이 남는 누락이다.** 실패 지표와 `eventId` 가 든 ERROR 로그가 남아 알림을 걸 수 있고 사람이 다시 보낼 수 있다.
- **주문 C 는 흔적이 없는 누락이다.** record 가 producer 버퍼에서 broker 로 가기 전에 프로세스가 죽어, `whenComplete` 가 불리지 않았다. 성공도 실패도 세지 않았고 로그도 없다. 알림을 걸 수 없고, 다시 보낼 단서(`eventId`)도 남지 않는다. 지표로는 이 누락을 막을 수도 찾을 수도 없다.
- 주문 C 의 `linger.ms=60000` 은 그 순간을 매번 같게 만드는 수단이다. 운영의 `linger.ms` 기본값(0 ~ 5 ms)에서도 commit 과 broker ack 사이에는 늘 이 창이 있다. 짧을 뿐 없어지지 않는다.
- 세 주문의 차이는 코드가 아니라 broker 상태와 프로세스 종료 시점뿐이다. W7 은 이 스크립트를 outbox 경로로 다시 돌려 주문 A 와 C 가 복구되는지 본다.

## 복제 (선택 심화 13, EXP-004 보강) — 단계별 출력은 `docs/week6/cluster/result.md`

별도 compose 로 controller 1 대와 broker 3 대(Kafka 3.5.1 KRaft)를 띄운다. broker 는 `docker pause` 로 멈추고 `docker kill` 로 강제 종료한다. topic 은 partition 1 · 복제 수 3(broker 1 · 2 · 3, 선호 leader 1) · `min.insync.replicas=2` · unclean leader election 꺼짐이다. producer 는 broker 컨테이너 안의 `kafka-producer-perf-test.sh` 이고, 끝 offset 은 high watermark 로 읽는다. controller 를 broker 와 나눈 것은 broker 2 대를 멈춰도 controller 가 살아 있어야 ISR 축소와 `NOT_ENOUGH_REPLICAS` 를 볼 수 있어서다.

| | 장애 | acks | 결과 |
|---|---|---|---|
| E1 | 없음 | all | 100 건 성공, 끝 offset 100, ISR 1 · 2 · 3 |
| E2 | follower 1 대 정지 | all | 100 건 성공, ISR 1 · 2 — `min.insync.replicas=2` 를 채운다 |
| E3 | follower 2 대 정지 | all | **20 건 모두 실패.** broker 가 `NOT_ENOUGH_REPLICAS` 로 97 번 거절했고, producer 는 재시도하다 `delivery.timeout.ms` 에 `TimeoutException` 으로 포기했다. 끝 offset 그대로 |
| | | 1 | 20 건 성공, 끝 offset +20 — 복제본 하나뿐인데 ack 를 준다 |
| E4 | follower 를 멈춰 복제를 막고 leader 강제 종료 | 1 | **ack 받은 50 건을 모두 잃었다.** 새 leader(2)에 없고, 돌아온 옛 leader 도 새 leader 에 맞춰 log 를 잘라 끝 offset 220 그대로 |
| E5 | 같은 장애 | all | ack 받은 record 0 건 · 잃은 record 0 건 — 복제되지 않은 record 에는 ack 가 가지 않았다 |
| E6 | follower 를 9 초보다 오래 멈춘 뒤 leader 강제 종료 | — | **leader 없음(offline).** controller 가 heartbeat 가 끊긴 follower 를 ISR 에서 빼(`broker.session.timeout.ms` 9 초) ISR 이 leader 하나뿐이었고, 그 leader 가 죽자 unclean 선출 없이는 고를 replica 가 없다 |
| E7 | 20 건/초로 보내는 중 leader 강제 종료 | all | **200 건 모두 성공, 오류 0.** 최대 지연 22.4 초, 새 leader 2 |

- **`acks=all` + `min.insync.replicas=2` 는 "ack 를 받았으면 잃지 않는다" 를 지킨다(E5).** 대가는 복제본이 모자랄 때 쓰기를 거절하는 것이다(E3).
- **`acks=1` 은 "ack 를 받았는데 사라지는" record 를 만든다(E4).** 우리가 `acks=all` 을 명시하는 이유다(`EVT-016`). producer 는 성공으로 세고 지표에도 남지 않으므로, 흔적 없는 누락이 하나 더 생기는 셈이다.
- **정합성을 위해 가용성을 내준다(E6).** unclean 선출을 켜면 E6 에서 broker 2 나 3 이 leader 가 되어 쓰기는 이어지지만, E4 처럼 이미 ack 된 record 를 잃을 수 있다. 우리는 끈다.
- **leader 교체는 `max.block.ms` 의 일이 아니다(E7, 설계 3.7 의 뒤집을 조건).** producer 는 metadata 를 이미 들고 있어 `send()` 는 막히지 않았고, record 가 버퍼에서 새 leader 를 기다렸다가 전부 전달됐다. 걸린 시간은 controller 가 죽은 broker 를 알아차리는 `broker.session.timeout.ms`(9 초)와 재시도가 겹친 22 초였다. 이 시간은 `delivery.timeout.ms`(우리는 기본 120 초) 안이라 실패하지 않는다. `max.block.ms` 1 초를 바꿀 근거는 나오지 않았다.

## 처리량 (선택 심화 14)

partition 3 개인 격리 topic 에 확정 이벤트 2,000 건을 미리 쌓고, 실제 reader 와 handler 를 붙인 컨테이너가 처리 기록 2,000 건을 남길 때까지의 시간과 offset commit 횟수를 잰다. 한 record 는 DB TX 하나(처리 기록 1 + 활동 2 insert)다. 300 건으로 한 번 데운 뒤 3 번 돌렸고, 표의 건/초는 세 번의 중앙값이다.

| concurrency | commit 단위 | commit 응답 | 건/초 (세 번) | 중앙값 | offset commit |
|---|---|---|---|---|---|
| 1 | poll 묶음 (`BATCH`) | 동기 | 237 · 213 · 224 | **224** | 4 |
| 3 | poll 묶음 (`BATCH`) | 동기 | 671 · 565 · 493 | **565** | 6 |
| 1 | record 마다 (`RECORD`) | 동기 | 216 · 218 · 213 | **216** | 2,000 |
| 3 | record 마다 (`RECORD`) | 동기 | 407 · 473 · 415 | **415** | 2,000 |
| 1 | record 마다 (`RECORD`) | 비동기 | 259 · 155 · 253 | **253** | 2,000 |
| 3 | record 마다 (`RECORD`) | 비동기 | 822 · 566 · 425 | **566** | 2,000 |

- **concurrency 1 → 3 이 가장 크다.** `BATCH` 에서 224 → 565 건/초(약 2.5 배)다. 스레드가 partition 마다 하나라 DB TX 가 나란히 돈다. 지금 설정(concurrency 3)을 고른 이유는 재시도 격리였는데, 처리량에서도 이득이 확인됐다.
- **commit 단위는 스레드가 많을수록 차이가 난다.** 스레드 1 개에서는 `BATCH` 224 와 `RECORD` 216 이 거의 같다 — 병목이 DB TX 라 commit 왕복이 묻힌다. 스레드 3 개에서는 565 대 415 로 `BATCH` 가 약 36 % 빠르다. commit 횟수는 6 대 2,000 이다.
- **record 마다 commit 해도 응답을 기다리지 않으면(비동기) `BATCH` 와 비슷해진다**(566). 다만 세 번의 값이 425 ~ 822 로 가장 흔들렸다.
- 설계 4.4 의 "처리량이 모자랄 때 푸는 순서"(concurrency → 비동기 commit → 묶음 insert)가 실측과 맞는다. 묶음 insert(batch listener)는 운영 코드를 바꿔야 해서 재지 않았다.
- 2,000 건을 미리 쌓는 producer 는 실험 전용이고 멱등을 끈다. 새로 만든 topic 의 첫 batch 가 leader 가 정해지기 전에 도착해 `NOT_LEADER_OR_FOLLOWER` 를 받은 뒤, 멱등 producer 가 `OUT_OF_ORDER_SEQUENCE_NUMBER` 를 122 번 받으며 그 partition 에서 막혔다(Kafka 3.5 broker). 중복이 생겨도 처리 기록이 거르므로 측정에 영향이 없다. 운영 producer 는 멱등을 그대로 켜 두고, topic 을 미리 만들고 기동 때 metadata 를 받으므로 이 경우를 만나지 않는다.

## DB 무응답 (T-5) — `DbSilenceExperimentTest`

실험 전용 MySQL 컨테이너에 붙인 streamer 컨텍스트에서, 그 MySQL 을 `docker pause` 로 멈춘 채 확정 이벤트를 보낸다. 공용 테스트 DB 는 건드리지 않는다.

| 값 | 결과 |
|---|---|
| 멈춘 뒤 첫 실패까지 | 10.5 초 — `socketTimeout=10000` 이 예외를 만들었다 |
| 원인 | `ConnectionIsClosedException` → 그 record 에서 다시 시도 |
| 멈춘 동안 컨테이너 | 실행 중 |
| DB 를 되살린 뒤 반영까지 | 1.2 초 — 사람 없이 이어졌다 |

- 상한이 없으면 DB 호출이 예외 없이 멈춰 error handler 가 돌지 않고, `max.poll.interval.ms`(5 분) 뒤 group 에서 빠져 rebalance 만 난다. 상한이 있어야 무기한 재시도가 실제로 돈다.

# 7주차 설계: 주문 확정과 전달할 사실을 함께 남기고, 중단 뒤 같은 구매 활동으로 수렴시킨다

규칙 ID 는 `docs/week7/requirements.md` 를 따른다. W6 설계(`docs/week6/design.md`)의 계약, 소비, fixture 는 그대로이고 바뀐 것만 적는다.
relay 를 CDC 로 바꿀 때의 근거는 `docs/week7/cdc-experiment.md` 에 있다.

## 1. 무엇이 바뀌는가

```
W6   결과 반영 TX ── commit ──▶ after-commit listener ──send──▶ order-events
                                 (commit 과 send 사이에 멈추면 사건이 사라진다)

W7   결과 반영 TX: 주문 CONFIRMED, 예약 소비, outbox PENDING ── commit
                                                                  │
     relay(전용 스레드 하나) ── 시각이 된 PENDING 을 고름 ──send──▶ order-events
                            ◀── broker 확인 ── 별도 TX 에서 PUBLISHED
```

W6 에서 사건이 사라질 수 있던 구간(commit 과 send 사이)에는 이제 같은 TX 로 저장한 행이 남는다.
대신 같은 사건이 두 번 나갈 수 있다(`RLY-006`). 중복은 소비 쪽 처리 기록(`ACT-002`, `ACT-004`)이 막는다.

## 2. 기록 (`OBX-*`)

### 2.1 기록하는 곳

`PaymentTransactions` 의 결과 반영 TX 에서 `CONFIRM_ORDER` 일 때만 `order.pullEvents()` 를 `OrderEventOutbox.append` 로 넘긴다.
W6 에서 `ApplicationEventPublisher` 로 넘기던 자리와 같다. 애그리거트는 최초 확정에서만 `OrderConfirmed` 를 쌓으므로(`EVT-010`, `EVT-011`)
반복 반영이나 `PENDING`, `UNKNOWN`, `FAILED` 결과는 행을 만들지 않는다(`OBX-006`). 직접 send 경로는 지웠고 relay 와 병행하지 않는다.

### 2.2 테이블

| 컬럼 | 규칙 |
|---|---|
| `event_id` | 유일 제약 `uk_outbox_event_id`(`OBX-002`). 원문이 요구한다 |
| `order_id`, `event_type`, `schema_version` | 원본 주문과 계약을 잇는다(`OBX-003`) |
| `topic`, `record_key`, `payload` | 생성할 때 정하고 `updatable = false`. payload 는 W6 과 같은 바이트(`MEDIUMBLOB`) |
| `status` | `PENDING`, `PUBLISHED`, `BLOCKED` |
| `attempt_count`, `next_attempt_at`, `last_error` | 재시도 관찰용. `next_attempt_at` 은 DB 기본값 `UTC_TIMESTAMP(6)` 으로 들어간다(`OBX-005`) |
| `retry_since` | 재시도를 시작한 시각. insert 때 DB 기본값 `UTC_TIMESTAMP(6)` 으로 들어가고, 재개하면 그 시각으로 바뀐다. 시간 상한(3.5)의 기준이다 |
| `published_at` | relay 가 DB 시각으로 채운다 |
| `created_at`, `updated_at` | `AuditEntity` |

인덱스 `idx_outbox_due(status, next_attempt_at, id)` 는 relay 가 고르는 조건, 정렬과 같다(`RLY-002`).

payload 를 `MEDIUMBLOB` 으로 두는 이유는 payload 를 읽는 쪽 대부분이 바이트가 그대로라는 데 기대기 때문이다.
relay 는 저장된 바이트를 `ByteArraySerializer` 로 보내고, 복구는 다시 만든 사건과 바이트로 비교하며(4 장), 계약은 공유 예시와 바이트까지 같다(W6 `EVT-003`).
`JSON` 타입은 키 순서와 공백을 정규화해 이 조건을 깨고, `TEXT` 는 JDBC 에서 문자열로 바뀌었다가 돌아온다. 사람은 `convert(payload using utf8mb4)` 로 읽는다(7.1).
**뒤집을 조건**: 사람이 payload 를 자주 직접 읽어야 하거나, 복구 판정을 바이트가 아닌 필드 비교로 바꿀 때. 그때는 `TEXT`(utf8mb4, `_bin` collation)로 바꾼다.

시각은 DB 에 맡긴다. 고르는 조건(`next_attempt_at <= utc_timestamp(6)`)과 다음 시각 계산이 같은 시계를 봐야 해서다.
앱 시계로 쓰면 앱 서버와 DB 의 시계 차이만큼 행을 일찍 또는 늦게 고른다. **뒤집을 조건**: 행을 DB 밖(예: 메모리 큐)에서 고르게 될 때.

### 2.3 직렬화는 TX 안에서

`OrderEventOutboxImpl.append` 가 `OrderConfirmedMessage` 를 바이트로 만든 뒤 저장한다. 실패하면 `IllegalStateException` 이 결과 반영 TX 밖으로 나가 확정까지 rollback 한다(`OBX-008`).
relay 는 저장된 바이트만 보내고 상품 정보를 다시 읽지 않는다. 확정 뒤 상품 이름이나 가격이 바뀌어도 나가는 사건은 확정 당시의 내용이다.

직렬화 실패는 테스트로 만들지 않는다. 문자열과 숫자만 담은 record 의 Jackson 직렬화를 실제로 실패시킬 방법이 없고, 직렬화기를 갈아 끼우면 테스트 더블이다.
그래서 직렬화가 `append` 안, 즉 TX 안에서 일어난다는 코드 구조로 보장하고, rollback 자체는 insert 실패로 확인한다(`EXP-010`).
**뒤집을 조건**: payload 에 직렬화가 실패할 수 있는 값(사용자 입력 문자열의 인코딩 등)이 들어올 때.

## 3. relay (`RLY-*`)

### 3.1 한 실행 흐름

- `outbox.relay.enabled=true` 인 인스턴스에서만 `OrderEventRelay.Schedule` 이 뜬다. 기본은 꺼져 있고, 로컬에서는 한 인스턴스만 켠다(`RLY-001`).
- 스레드는 `outbox-relay` 하나(`newSingleThreadScheduledExecutor`)이고 `scheduleWithFixedDelay` 로 돌아서 회차가 겹치지 않는다. 결제 sweeper 와 스레드가 달라서 relay 가 broker 를 기다리는 동안에도 결제 복구는 밀리지 않는다.
- 한 회차는 묶음이 꽉 차 있는 동안 이어서 보낸다(`relayUntilDrained`). 쌓인 행이 있으면 다음 주기를 기다리지 않는다.
- 두 인스턴스가 relay 를 켜면 같은 행을 두 번 보낸다. 막는 장치는 없다. 소비 쪽이 중복을 막지만 순서는 더 흐트러진다.
  **뒤집을 조건**: relay 를 여러 대로 늘려야 할 때. 소유권(`SELECT … FOR UPDATE SKIP LOCKED` 또는 lease)과 순서를 함께 정한다. 행을 나눠 가지면 같은 주문의 두 사건을 서로 다른 relay 가 집어서 broker 에 넣는 순서가 바뀔 수 있다.

### 3.2 고르기

`status = 'PENDING' and next_attempt_at <= utc_timestamp(6) order by id limit 100` 으로 고른다. 읽기 전용 TX 로 고르고 바로 끝내서,
broker 를 기다리는 동안에는 DB 커넥션도 잠금도 쥐지 않는다(`RLY-007`).

### 3.3 묶음 전송과 대기 상한

- 고른 묶음(최대 100 건)을 모두 `send` 한 뒤 future 를 차례로 기다린다. 동시에 기다리는 future 는 묶음 크기를 넘지 않는다(`RLY-007`). producer 가 멱등(`enable.idempotence=true`)이라 같은 partition 안에서는 보낸 순서대로 기록된다.
- 대기 상한은 producer 의 `delivery.timeout.ms`(30 초)에 5 초를 더한 값이다. producer 는 `delivery.timeout.ms` 안에 future 를 반드시 끝내므로,
  relay 가 기다리기를 포기한 뒤에 send 가 성공하는 경우가 생기지 않는다(`RLY-006`). 상한을 producer 설정에서 읽어서 설정을 바꿔도 두 값이 어긋나지 않는다.
- `max.block.ms` 는 1 초다(W6 `EVT-014`). metadata 나 버퍼를 기다리다 이 시간을 넘으면 send 를 시작하지 못한 실패로 분류한다.
- **뒤집을 조건**: 처리량이 모자라 묶음을 키우거나 회차를 겹쳐야 할 때. 같은 key 의 순서를 먼저 정한다.

### 3.4 결과 분류

| 결과 | 판정 | 행 |
|---|---|---|
| future 성공 | `Published` | `PUBLISHED`, `published_at` |
| `RecordTooLargeException`, `InvalidTopicException` | `Rejected`. 같은 record 를 다시 보내도 결과가 같다 | `BLOCKED`, `last_error` (`RLY-005`) |
| 그 밖의 실패, timeout, interrupt, send 시작 실패 | `Unconfirmed`. broker 에 닿았는지 모른다 | `PENDING`, 다음 시각, `last_error` (`RLY-004`). 시간 상한을 넘겼으면 `BLOCKED` (3.5, `RLY-009`) |

- 거절은 record 의 내용이나 목적지 때문에 broker 가 받을 수 없는 경우로 좁힌다. 인증이나 권한 오류는 설정을 고치면 같은 record 가 나가므로 `Unconfirmed` 로 둔다. 고친 뒤 사람이 손대지 않아도 나간다.
- `last_error` 에는 `예외 종류: 메시지` 를 1000 자까지 남긴다. payload 는 담지 않는다(`RLY-008`).
- **뒤집을 조건**: 거절로 볼 예외가 더 생길 때(예: schema registry 의 호환성 거절). 같은 집합에 더한다.

Kafka DLQ topic 은 두지 않는다. 보내는 쪽의 dead letter 는 outbox 의 `BLOCKED` 행이다.
broker 가 거절한 record 는 DLQ topic 도 같은 크기 제한과 같은 broker 장애를 겪으므로 들어가지 못할 수 있다. DB 에 남은 행은 broker 상태와 상관없이 확정 당시의 바이트를 갖고 있고, 재처리는 재개(5 장)로 한다.
받는 쪽의 dead letter 는 W6 의 보류 테이블(`order_event_hold`)이어서 두 쪽 모두 DB 에 있다.
**뒤집을 조건**: 송신 실패를 다른 시스템이 구독해 처리해야 할 때.

### 3.5 다시 시도하는 간격

`Unconfirmed` 의 다음 시각은 `1 초 × 2^attempt_count` 이고 상한은 1 분이다. 1 분 상한 덕분에 broker 가 회복되면 늦어도 1 분 안에 다시 시도한다.

재시도에는 횟수 상한 대신 시간 상한을 둔다. `retry_since` 부터 `outbox.relay.give-up-after`(기본 24 시간)가 지난 행이 또 `Unconfirmed` 로 끝나면 `PENDING` 대신 `BLOCKED` 로 옮긴다.
판정은 `retryLater` 의 update 안에서 DB 시각으로 한다(`OBX-005` 와 같은 이유).

- 3.4 의 거절 분류는 확실히 다시 해도 안 되는 경우를 바로 격리하고, 시간 상한은 분류가 놓친 영구 오류(끝내 풀리지 않는 권한 오류 등)를 걸러 사람에게 넘긴다. 분류만 두면 그런 행이 1 분 간격으로 끝없이 `PENDING` 에 남는다.
- 횟수로 자르지 않는 이유는 일시 장애로 `BLOCKED` 가 되면 장애가 끝난 뒤에도 사람이 재개해야 하기 때문이다. 백오프가 1 분에서 멈추므로 횟수 상한은 결국 시간 상한과 같은 효과이면서 뜻이 덜 분명하다.
- 24 시간은 대부분의 broker 장애보다 길고 다음 근무일 안에 드러나는 길이다. 측정해서 정한 값은 아니다.
- 기준을 생성 시각이 아닌 `retry_since` 로 두는 이유는 재개 때문이다. 생성 시각으로 재면 재개한 행이 한 번만 실패해도 곧바로 다시 `BLOCKED` 가 된다. 재개가 `retry_since` 를 지금으로 바꿔서 재개한 행도 같은 시간을 다시 받는다.

**뒤집을 조건**: broker 장애가 24 시간보다 자주 길어져 회복 뒤 사람이 재개해야 하는 행이 쌓일 때(상한을 늘린다), 또는 영구 오류를 하루보다 빨리 알아야 할 때(PENDING 최장 대기 시간에 알림을 건다).

### 3.6 시도 수

`attempt_count` 는 결과를 기록한 시도의 수다. 결과 TX(`markPublished`, `markBlocked`, `retryLater`)에서 1 을 더한다.
send 뒤 결과를 기록하기 전에 멈춘 시도는 세지 않고, broker 가 받은 횟수와도 다르다. 그 횟수는 broker 의 record 로만 알 수 있다.
이 값은 다음 시각 계산에만 쓰고 `BLOCKED` 판단에는 쓰지 않으므로(3.5) 세지 못한 시도가 있어도 판단이 틀어지지 않는다.

### 3.7 상태 전이와 쓰는 쪽

| 전이 | 쓰는 쪽 |
|---|---|
| (없음) → `PENDING` | 결과 반영 TX(`OBX-001`), 복구(`RCV-001`) |
| `PENDING` → `PUBLISHED`, `BLOCKED`, `PENDING`(다음 시각) | relay 의 결과 TX 하나 |
| `BLOCKED` → `PENDING` | 재개(`RCV-004`, 5 장) |

모든 update 에 `where … and status = '현재 상태'` 조건을 건다. 늦게 도착한 결과나 다른 경로가 이미 바꾼 행을 덮지 않는다.

### 3.8 종료

`@PreDestroy` 가 executor 를 멈추고 40 초 기다린다. 대기 상한(35 초)보다 길어서 진행 중인 묶음이 결과 기록까지 마칠 수 있다.
40 초를 넘기면 `shutdownNow` 하고, 결과를 기록하지 못한 행은 `PENDING` 으로 남아 다음 기동 때 다시 나간다.

## 4. 복구 (`RCV-001` ~ `RCV-003`)

확정됐지만 outbox 가 없는 주문(W6 에서 빠진 것, 행이 사라진 것)은 `POST /api-admin/v1/orders/{orderId}/confirmed-event/recovery` 로 복구한다.

- `OrderEventRecovery` 가 주문 행을 잠그고(`getForUpdate`) 저장된 사실로 `Order.confirmedEvent()` 를 불러 사건을 다시 만든다. 이 잠금 때문에 복구끼리, 그리고 복구와 최초 확정이 겹치지 않는다.
- `appendIfAbsent` 는 같은 `eventId` 가 없으면 `PENDING` 으로 추가한다(`APPENDED`). 있으면 type, version, key, payload 를 비교해서 같으면 그대로 두고(`ALREADY_PRESENT`), 다르면 `ORDER_EVENT_CONFLICT`(409)로 실패한다. 이미 `PUBLISHED` 인 행을 `PENDING` 으로 되돌리지 않는다.
- 확정되지 않은 주문은 `ORDER_NOT_CONFIRMED`(409)로 거절한다. `confirmedAt` 이 없는 확정 주문은 사실이 부족하므로 추측하지 않고 실패한다.
- PG, 재고, 결제는 건드리지 않는다(`RCV-002`).
- 관리자 API 로 두는 이유는 requirements Q-2 에 있다.

## 5. 재개 (`RCV-004`)

`BLOCKED` 행은 원인(record 크기, topic 설정 등)을 고친 뒤 같은 행으로 다시 보낸다.

```
java -jar commerce-api.jar … --outbox.resume.event-ids=order-confirmed:v1:41,order-confirmed:v1:42
```

- 기동할 때 ApplicationRunner 가 각 `eventId` 를 `BLOCKED` 에서 `PENDING` 으로, `next_attempt_at` 과 `retry_since` 를 `utc_timestamp(6)` 으로 바꾸고 결과를 로그로 남긴다. `attempt_count` 와 `last_error` 는 그대로 둬서 몇 번 실패했던 행인지 남긴다.
- 실제로 보내는 일은 relay 가 한다. 재개는 상태만 바꾸므로 relay 와 실행 흐름이 겹치지 않는다. relay 가 꺼진 인스턴스에서 재개하면 relay 를 켠 인스턴스가 보낸다.
- `BLOCKED` 가 아닌 `eventId`(없는 것, `PENDING`, `PUBLISHED`)는 바꾸지 않고 로그로 알린다. 기동은 막지 않는다. 재개 대상을 잘못 적었다고 앱이 뜨지 않으면 다른 기능까지 멈추기 때문이다.
- 진입점을 기동 인자로 둔 이유는 requirements Q-1 에 있다.

## 6. 검증

### 6.1 원자성과 중단 (`EXP-010` ~ `EXP-012`)

| 장면 | 테스트 |
|---|---|
| 같은 TX 기록, DB 시각 | `OrderEventOutboxAtomicityTest.confirmAppendsPendingOutbox` |
| 같은 거래를 다시 반영해도 한 행 | `OrderEventOutboxAtomicityTest.repeatedSuccessKeepsOneRow` |
| outbox insert 실패로 확정 rollback. 주문 `PENDING`, 결제 상태와 예약한 재고 그대로, PG 결제 생성 증가 없음 | `OrderEventOutboxAtomicityTest.outboxFailureRollsBackConfirmation`. 같은 `eventId` 행을 미리 넣어 실제 유일 제약 위반을 만든다 |
| rollback 뒤 같은 거래를 다시 반영하면 확정, 재고 한 번 소비, outbox 한 행, PG 결제 생성 증가 없음 | `OrderEventOutboxAtomicityTest.reapplyAfterRollbackConfirmsOnce` |
| commit 뒤 relay 전에서 relay 실행 | `OrderEventFlowE2ETest.confirmationReachesTopicOnlyThroughRelay` |
| broker 확인 뒤 기록 전 중단, 새 relay | `OrderEventRelayTest.resendsAfterStopBeforeRecord` |

중단은 보내고 기록하지 않는 방식으로 만든다. relay 의 회차는 `sendDue`(고르기, 보내기, 기다리기)와 `record`(결과 TX)로 나뉘어 있어서,
테스트는 `sendDue` 만 부르고 결과를 버린 뒤 새 relay 객체로 회차를 돌린다. 행을 직접 덮어쓰지 않고, 이것을 프로세스 강제 종료라고 부르지 않는다(`EXP-012`).

### 6.2 재발행이 구매 활동을 늘리지 않음 (`EXP-013`)

발행 쪽과 소비 쪽을 각자의 테스트로 확인하고, 둘을 공유 계약(`modules/kafka` testFixtures 의 `order-confirmed-v1.json`)으로 잇는다.
두 앱을 한 테스트에서 함께 띄우지 않는다. 이유는 requirements Q-3 에 있다.

| 고리 | 확인하는 것 | 테스트 |
|---|---|---|
| 1 | 앱이 만든 payload 가 공유 계약 예시와 필드, 값, 시각 표기까지 같다 | commerce-api `OrderConfirmedMessageTest.matchesContractExample` |
| 2 | relay 가 broker 확인 뒤 기록 전에 멈췄다가 다시 돌면, 저장된 같은 바이트가 같은 key 로 다른 offset 에 두 번 나간다 | commerce-api `OrderEventRelayTest.resendsAfterStopBeforeRecord` |
| 3 | 공유 계약 예시가 같은 key 로 다른 offset 에 두 번 와도 처리 기록 1 건, 구매 활동 2 행(수량 2, 1)이고, committed offset 은 두 record 를 모두 지난다 | commerce-streamer `OrderConfirmedListenerTest.resentEventAtNewOffsetIsNoOp` |

1 과 2 로 relay 가 계약 예시와 같은 바이트를 두 번 보낸다는 것이, 3 으로 그 두 번이 효과를 한 번만 남긴다는 것이 확인된다.
3 은 W6 의 같은 offset 재전달(`EXP-005`)과 다른 장면이다. relay 의 재발행은 commit 되지 않은 위치를 다시 읽는 것이 아니라 새 offset 의 새 record 라서 따로 확인한다.

두 앱을 함께 띄우는 테스트로 확인할 때보다 놓치는 것은 배선이다. topic 이름, group, 직렬화 설정이 두 앱에서 어긋나면 위 세 고리는 모두 통과해도 실제로는 이어지지 않는다.
이 배선은 `EXP-014` 의 재현 스크립트가 두 앱을 프로세스로 띄워 한 번 확인한다.

### 6.3 W6 누락 재현과 복구 (`EXP-014`)

`docs/week7/recovery/run.sh` 를 한 번 실행해서 확인한다. 구성은 W6 의 `docs/week6/gap/run.sh` 와 같다(실행 전용 MySQL 컨테이너, compose broker, 프로세스로 띄운 두 앱과 PG fixture, `kcat` 으로 관찰).

1. `upstream/seeun0210`(W6)을 git worktree 로 받아 api 와 streamer 의 `bootJar` 를 만든다. W7 jar 도 만든다.
2. W6 api, W6 streamer, PG fixture 를 띄운다.
3. B: 주문을 확정하고 구매 활동이 생길 때까지 기다린다.
4. A: W6 api 를 `linger.ms=60000` 으로 다시 띄워 확정하고, 확정이 commit 된 뒤 record 가 producer 버퍼에 있는 동안 `kill -9` 한다. 지표에도 남지 않는 누락이고, W6 Q-6 의 뒤집을 조건에 해당한다.
5. 두 주문의 사실, PG 거래 식별자, PG create 누적 횟수 C, 외부 거래 수 T 를 기록한다. 이를 위해 PG fixture 의 제어 포트에 호출 수 조회를 추가한다.
6. W7 api 를 같은 DB 에 `ddl-auto=update` 로 띄우고 relay 를 켠다. outbox 테이블만 추가되고 기존 행은 그대로다. streamer 는 소비 계약이 같으므로 W6 것을 그대로 둔다.
7. 관리자 API 로 A 와 B 를 복구하고 relay 가 보내게 한다.
8. A 의 구매 활동이 생기고, B 의 활동은 그대로이며, 두 번째 복구도 아무것도 바꾸지 않는지 확인한다. PG create 는 C 그대로, 조회와 callback 은 늘지 않고, 외부 거래 수는 T 그대로여야 한다. 7 장의 운영 SQL 결과와 함께 `docs/week7/recovery/result.md` 에 쓴다.
9. 이 실행이 만든 프로세스, 컨테이너, topic, worktree 만 지운다. compose 의 kafka 는 실행 전에 꺼져 있었을 때만 멈춘다.

B 를 A 보다 먼저 만든다. A 를 만들 때 W6 api 를 `kill -9` 하므로, B 를 먼저 끝내야 W6 api 를 한 번만 다시 띄우면 된다.
실행 결과에서 B 의 두 record(W6 가 보낸 것과 W7 이 복구해서 보낸 것)는 바이트까지 같았다. 저장된 사실로 다시 만든 payload 가 W6 가 보낸 것과 같다는 뜻이다.

## 7. 운영 절차 (`OPS-*`)

### 7.1 확인하는 쿼리

```sql
-- 상태별 건수, 가장 이른 생성 시각, 최대 시도 수
select status, count(*) rows_, min(created_at) oldest_created, max(attempt_count) max_attempts
from order_event_outbox group by status;

-- PENDING 최장 대기 시간(초). 가장 오래 기다린 PENDING 이 만들어진 뒤 지난 시간. relay 정지, backoff 누적, 처리량 부족을 본다
select timestampdiff(second, min(created_at), utc_timestamp(6)) longest_pending_wait_s
from order_event_outbox where status = 'PENDING';

-- 실패를 반복하는 PENDING. 같은 원인이 계속되는지 본다
select event_id, attempt_count, next_attempt_at, last_error
from order_event_outbox where status = 'PENDING' and attempt_count > 0
order by created_at limit 20;

-- 미해결 BLOCKED
select event_id, order_id, attempt_count, last_error, updated_at
from order_event_outbox where status = 'BLOCKED' order by id;

-- payload 읽기. BLOB 이라 그대로 조회하면 16 진수로 보인다
select event_id, status, convert(payload using utf8mb4) payload_json,
       json_extract(convert(payload using utf8mb4), '$.confirmedAt') confirmed_at
from order_event_outbox where order_id = ?;

-- PUBLISHED 인데 1 분 넘게 소비 처리 기록이 없는 사건. PUBLISHED 는 소비 완료가 아니다(OPS-002)
select o.event_id, o.published_at
from order_event_outbox o
left join order_confirmation_receipt r on r.event_id = o.event_id
where o.status = 'PUBLISHED' and r.event_id is null
  and o.published_at < utc_timestamp(6) - interval 1 minute;
```

발행 성공과 실패 수는 W6 의 지표 `order.event.publish{result, cause}` 로도 본다. 마지막 쿼리는 운영 확인을 위해서만 streamer 의 테이블을 읽는다. 코드가 상대 앱의 테이블을 읽지 않는다는 W6 Q-2 는 그대로다.

outbox 조회 API 는 두지 않는다. 위 SQL 로 원문의 확인 요구를 채울 수 있고, payload 도 `convert` 한 줄로 읽힌다.
**뒤집을 조건**: DB 에 접근하지 않는 사람(운영, CS)이 `BLOCKED` 나 오래된 `PENDING` 을 봐야 할 때. 읽기 전용 관리자 조회를 두고, payload 는 응답을 만들 때 JSON 으로 푼다. 상태를 바꾸는 동작은 넣지 않는다(재개는 5 장).

### 7.2 상태별 대처

| 상태 | 대처 |
|---|---|
| `PENDING` 이 쌓이고 `last_error` 가 broker 연결 실패나 timeout | 할 일 없음. broker 가 돌아오면 1 분 안에 같은 행이 나간다 |
| `PENDING` 에 연결 외의 같은 `last_error` 가 반복 | 원인(권한, 설정)을 고치면 같은 행이 나간다. payload 는 고치지 않는다 |
| PENDING 최장 대기 시간이 늘어나는데 `attempt_count` 가 0 | relay 가 돌지 않는 상태다. `outbox.relay.enabled` 를 켠 인스턴스가 떠 있는지 본다 |
| `BLOCKED` 이고 `last_error` 가 거절(`RecordTooLargeException`, `InvalidTopicException`) | 원인(record 크기, topic)을 고친 뒤 5 장의 재개 |
| `BLOCKED` 이고 `last_error` 가 그 밖의 오류 | 시간 상한(3.5)으로 옮겨진 행이다. 24 시간 넘게 풀리지 않은 원인을 찾아 고친 뒤 5 장의 재개 |
| `PUBLISHED` 인데 처리 기록 없음 | consumer lag, 보류(`order_event_hold`), 멈춘 listener 를 본다(W6 4.6). outbox 는 되돌리지 않는다 |
| 확정 주문인데 outbox 행이 없음 | 4 장의 복구 |

### 7.3 보존 (`OPS-003`)

자동 삭제는 두지 않는다. `PENDING` 과 `BLOCKED` 는 아직 전달되지 않은 사실이라, 지우면 그 사건을 다시 찾을 근거가 사라진다.

정리 정책을 정할 때는 다음 관계를 지킨다.

- 소비 쪽 처리 기록(`order_confirmation_receipt`)은 같은 사건이 다시 올 수 있는 기간보다 오래 남아야 한다. 먼저 지우면 다시 온 사건이 효과를 한 번 더 만든다.
- 같은 사건이 다시 오는 경로는 셋이다. topic 에 남은 record 의 재전달(broker 기본 보존 기간을 바꾸지 않았다), `PUBLISHED` 를 기록하기 전에 멈춘 relay 의 재발행, 복구 API.
- 복구 API 는 주문 행에서 사건을 언제든 다시 만든다. `PUBLISHED` 행을 지우면 그 주문은 outbox 가 없는 확정 주문이 되고, 복구가 같은 사건을 다시 보낼 수 있다.
  그래서 `PUBLISHED` 행을 지우려면 처리 기록을 영구 보존하거나, 처리 기록 보존 기간보다 오래된 주문의 복구를 거절해야 한다.
  어느 쪽을 고를지는 outbox 행을 보존할 사실로 볼지, 주문에서 언제든 다시 만들 수 있는 캐시로 볼지에 달려 있다.

## 8. 알려진 한계와 지금 고르지 않은 선택지

| 한계 | 지금 | 고를 수 있는 선택지 |
|---|---|---|
| 같은 주문의 사건 순서 | 행마다 따로 재시도해서, 앞 행이 backoff 중이거나 `BLOCKED` 이면 뒤 행이 먼저 나간다. 지금은 사건이 `OrderConfirmed` 하나라 드러나지 않는다 | key 별로 앞 행이 나갈 때까지 뒤 행을 고르지 않기, 소비 쪽에서 주문별 version 판정, CDC 처럼 위치 하나로 꺼내기(`cdc-experiment.md` 6 장) |
| relay 한 대 | 그 인스턴스가 멈추면 발행도 멈춘다. 확정은 계속된다 | 다중 relay 와 소유권(3.1) |
| 복제 장애 | 단일 broker 라 `acks=all` 이 복제 장애에 견디는지 보여 주지 못한다 | W6 의 broker 3 대 실측(`docs/week6/cluster/result.md`) |
| polling 비용 | 1 초마다 인덱스 범위 조회 | CDC(`cdc-experiment.md`) |
| 직렬화 실패 | 코드 구조로만 보장한다(2.3) | 없음 |

## 9. 작업 단계

| # | 할 일 | 규칙 | 상태 |
|---|---|---|---|
| 1 | 같은 TX 기록, rollback | `OBX-*`, `EXP-010` | 완료 |
| 2 | 단일 relay, 결과 분류, backoff | `RLY-*`, `EXP-011`, `EXP-012` | 완료 |
| 3 | 복구 관리자 API | `RCV-001` ~ `RCV-003` | 완료 |
| 4 | `BLOCKED` 재개 | `RCV-004` | 완료(`OrderEventResumeTest`) |
| 4-1 | 재시도 시간 상한 | `RLY-009` | 완료(`OrderEventRelayGiveUpTest`) |
| 5 | rollback 뒤 재고와 PG create 확인 보강 | `OBX-007`, `EXP-010` | 완료(`OrderEventOutboxAtomicityTest`) |
| 6 | 재발행이 구매 활동을 늘리지 않음 | `EXP-013` | 완료(6.2 의 세 고리) |
| 7 | W6 누락 재현과 복구, 운영 SQL 실행 결과 | `EXP-014`, `OPS-001` | 완료(`docs/week7/recovery/result.md`) |
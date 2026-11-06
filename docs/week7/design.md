# 커머스 설계 — 7주차 · 주문 확정과 전달할 사실을 함께 남기고, 중단 뒤 같은 구매 활동으로 수렴시킨다

규칙 ID 는 `docs/week7/requirements.md` 를 따른다. W6 설계(`docs/week6/design.md`)의 계약 · 소비 · fixture 는 그대로이고, 바뀐 것만 적는다.
outbox 너머의 질문(순서 · 대사 · 사가 · 다중 relay)은 `docs/week7/beyond-outbox.md`, relay 를 CDC 로 바꿀 때의 근거는 `docs/week7/cdc-experiment.md` 에 있다.

## 1. 무엇이 바뀌는가

```
W6   결과 반영 TX ── commit ──▶ after-commit listener ──send──▶ order-events
                                 (commit 과 send 사이에 멈추면 사건이 사라진다)

W7   결과 반영 TX: 주문 CONFIRMED · 예약 소비 · outbox PENDING ── commit
                                                                  │
     relay(전용 스레드 하나) ── 시각이 된 PENDING 을 고름 ──send──▶ order-events
                            ◀── broker 확인 ── 별도 TX 에서 PUBLISHED
```

사라질 수 있던 구간(commit ↔ send)이 **같은 TX 에 남은 행**으로 바뀐다. 대신 같은 사건이 두 번 나갈 수 있고(`RLY-006`), 그것은 소비 쪽 처리 기록(`ACT-002` · `ACT-004`)이 흡수한다.

## 2. 기록 (`OBX-*`)

### 2.1 어디서 — 결과 반영 경계

`PaymentTransactions` 의 결과 반영 TX 에서 `CONFIRM_ORDER` 일 때만 `order.pullEvents()` 를 `OrderEventOutbox.append` 로 넘긴다.
W6 에서 `ApplicationEventPublisher` 로 넘기던 자리 그대로다. 애그리거트가 최초 확정에서만 `OrderConfirmed` 를 쌓으므로(`EVT-010` · `EVT-011`)
반복 반영 · `PENDING` · `UNKNOWN` · `FAILED` 는 행을 만들지 않는다(`OBX-006`). 직접 send 경로는 지웠다 — 병행하지 않는다.

### 2.2 테이블

| 컬럼 | 규칙 |
|---|---|
| `event_id` | 유일 제약 `uk_outbox_event_id`(`OBX-002`). 원문이 요구한다 |
| `order_id` · `event_type` · `schema_version` | 원본 주문 · 계약 연결(`OBX-003`) |
| `topic` · `record_key` · `payload` | 생성 때 정하고 `updatable = false`. payload 는 W6 과 같은 바이트(`MEDIUMBLOB`) |
| `status` | `PENDING` · `PUBLISHED` · `BLOCKED` |
| `attempt_count` · `next_attempt_at` · `last_error` | 재시도 관찰. `next_attempt_at` 은 DB 기본값 `UTC_TIMESTAMP(6)` 으로 insert 된다(`OBX-005`) |
| `published_at` | relay 가 DB 시각으로 채운다 |
| `created_at` · `updated_at` | `AuditEntity` |

인덱스 `idx_outbox_due(status, next_attempt_at, id)` 는 relay 의 고르기 조건 · 정렬과 같다(`RLY-002`).

- **시각을 DB 에 맡기는 이유**: 고르기(`next_attempt_at <= utc_timestamp(6)`)와 다음 시각 계산이 같은 시계를 본다. 앱 시계로 쓰면 앱 서버와 DB 의 시계 차만큼 행이 일찍 · 늦게 고려진다. **뒤집을 조건**: 고르기를 DB 밖(예: 메모리 큐)에서 하게 될 때.

### 2.3 직렬화는 TX 안에서

`OrderEventOutboxImpl.append` 가 `OrderConfirmedMessage` 를 바이트로 만든 뒤 저장한다. 실패하면 `IllegalStateException` 이 결과 반영 TX 밖으로 나가 확정까지 rollback 한다(`OBX-008`).
relay 는 저장된 바이트만 보낸다 — 상품 정보를 다시 읽지 않으므로, 확정 뒤 상품 이름 · 가격이 바뀌어도 나가는 사건은 확정 당시의 것이다.

- **직렬화 실패는 테스트로 만들지 않는다.** 문자열 · 숫자만 담은 record 의 Jackson 직렬화를 실제로 실패시킬 방법이 없고, 갈아 끼우면 테스트 더블이다.
  보장은 코드 구조(직렬화가 `append` 안 · TX 안)에 기댄다. rollback 자체는 insert 실패로 확인한다(`EXP-010`). **뒤집을 조건**: payload 에 직렬화가 실패할 수 있는 값(사용자 입력 문자열의 인코딩 등)이 들어올 때.

## 3. relay (`RLY-*`)

### 3.1 한 실행 흐름

- `outbox.relay.enabled=true` 인 인스턴스에서만 `OrderEventRelay.Schedule` 이 뜬다. **기본은 꺼짐이고, 로컬에서 켜는 인스턴스는 하나다**(`RLY-001`).
- 스레드는 `outbox-relay` 하나(`newSingleThreadScheduledExecutor`)이고 `scheduleWithFixedDelay` 라 회차가 겹치지 않는다. 결제 sweeper 와 스레드를 나눠, relay 가 broker 를 기다리는 동안 결제 복구가 밀리지 않는다.
- 한 회차는 묶음이 꽉 차는 동안 이어서 보낸다(`relayUntilDrained`). backlog 가 있으면 주기를 기다리지 않는다.
- **두 인스턴스가 켜면 같은 행을 두 번 보낸다.** 막는 장치는 없다. 소비 쪽이 흡수하지만 순서는 더 흐트러진다. **뒤집을 조건**: relay 를 여러 대로 늘려야 할 때 — 소유권(`SELECT … FOR UPDATE SKIP LOCKED` · lease) · 순서를 함께 정한다(`beyond-outbox.md` 6 장).

### 3.2 고르기

`status = 'PENDING' and next_attempt_at <= utc_timestamp(6) order by id limit 100`. 읽기 전용 TX 로 고르고 바로 끝낸다.
broker 를 기다리는 동안 DB 커넥션 · 잠금을 쥐지 않는다(`RLY-007`).

### 3.3 묶음 전송과 대기 상한

- 고른 묶음(최대 100)을 모두 `send` 한 뒤 future 를 차례로 기다린다. future 수는 묶음 크기로 막힌다(`RLY-007`). producer 는 멱등(`enable.idempotence=true`)이라 같은 partition 안에서는 보낸 순서대로 기록된다.
- **대기 상한 = producer `delivery.timeout.ms`(30 초) + 5 초.** producer 는 `delivery.timeout.ms` 안에 future 를 반드시 끝낸다. 그보다 길게 기다리므로
  "relay 가 포기했는데 send 가 나중에 성공" 하는 경우가 생기지 않는다(`RLY-006`). 대기 상한을 producer 설정에서 읽어, 설정을 바꿔도 둘이 어긋나지 않는다.
- `max.block.ms` 는 1 초(W6 `EVT-014`)다. metadata · 버퍼를 기다리다 넘으면 send 가 시작도 못 한 실패로 분류된다.
- **뒤집을 조건**: 처리량이 모자라 묶음을 키우거나 회차를 겹쳐야 할 때 — 같은 key 의 순서(3.6)를 먼저 정한다.

### 3.4 결과 분류

| 결과 | 판정 | 행 |
|---|---|---|
| future 성공 | `Published` | `PUBLISHED` · `published_at` |
| `RecordTooLargeException` · `InvalidTopicException` | `Rejected` — 같은 record 를 다시 보내도 같은 결과 | `BLOCKED` · `last_error` (`RLY-005`) |
| 그 밖의 실패 · timeout · interrupt · send 시작 실패 | `Unconfirmed` — broker 에 닿았는지 모른다 | `PENDING` · 다음 시각 · `last_error` (`RLY-004`) |

- 거절은 **record 의 내용 · 목적지 때문에 broker 가 받을 수 없는 경우**로 좁힌다. 인증 · 권한 오류는 설정을 고치면 같은 record 가 나가므로 `Unconfirmed` 로 둔다 — 고친 뒤 사람이 손대지 않아도 나간다.
- `last_error` 는 `예외 종류: 메시지` 를 1000 자로 자른 것이다. payload 를 담지 않는다(`RLY-008`).
- **뒤집을 조건**: 거절로 볼 예외가 더 생길 때(예: schema registry 의 호환성 거절) — 같은 집합에 더한다.

### 3.5 다시 시도하는 간격

`Unconfirmed` 의 다음 시각은 `1 초 × 2^attempt_count`, 상한 1 분이다. 시도 상한은 두지 않는다 — broker 장애가 길어도 회복되면 같은 행이 나간다(원문 4 장).
1 분 상한은 broker 회복 뒤 늦어도 1 분 안에 다시 시도한다는 뜻이다. 반복 실패는 `attempt_count` · `last_error` 로 드러난다(7 장).

- **시도 예산(N 회 뒤 `BLOCKED`)을 두지 않는 이유**: 일시 장애로 `BLOCKED` 가 되면 회복 뒤에도 사람이 재개해야 한다. 같은 record 로 반드시 실패하는 경우는 3.4 의 거절이 이미 거른다.
  **뒤집을 조건**: 거절로 분류하지 못한 영구 오류가 `PENDING` 에 오래 머무는 것이 운영에서 관찰될 때.

### 3.6 시도 수의 의미

`attempt_count` 는 **결과를 기록한 시도의 수**다. 결과 TX(`markPublished` · `markBlocked` · `retryLater`)에서 1 을 더한다.
send 뒤 결과를 기록하기 전에 멈춘 시도는 세지 않는다. broker 가 받은 횟수와도 다르다 — 그것은 broker 의 record 로만 안다.
백오프 계산에만 쓰고 예산으로 쓰지 않으므로(3.5) 세지 못한 시도가 있어도 판단이 틀어지지 않는다.

### 3.7 상태 전이와 쓰는 쪽

| 전이 | 쓰는 쪽 |
|---|---|
| (없음) → `PENDING` | 결과 반영 TX(`OBX-001`), 복구(`RCV-001`) |
| `PENDING` → `PUBLISHED` · `BLOCKED` · `PENDING`(다음 시각) | relay 의 결과 TX 하나 |
| `BLOCKED` → `PENDING` | 재개(`RCV-004`, 5 장) |

모든 update 는 `where … and status = '현재 상태'` 다. 늦게 도착한 결과나 다른 경로가 이미 바꾼 행을 덮지 않는다.

### 3.8 종료

`@PreDestroy` 가 executor 를 멈추고 40 초 기다린다. 대기 상한(35 초)보다 길어, 진행 중인 묶음이 결과까지 기록하고 끝날 수 있다.
넘으면 `shutdownNow` 하고, 결과를 기록하지 못한 행은 `PENDING` 으로 남아 다음 기동에 다시 나간다.

## 4. 복구 (`RCV-001` ~ `RCV-003`)

확정됐지만 outbox 가 없는 주문(W6 에서 빠진 것, 행이 사라진 것)은 `POST /api-admin/v1/orders/{orderId}/confirmed-event/recovery` 로 복구한다.

- `OrderEventRecovery` 가 **주문 행을 잠그고**(`getForUpdate`) 저장된 사실에서 `Order.confirmedEvent()` 로 사건을 다시 만든다. 잠금이 복구끼리, 그리고 최초 확정과 겹치지 않게 한다.
- `appendIfAbsent`: 같은 `eventId` 가 없으면 `PENDING` 으로 추가(`APPENDED`), 있으면 type · version · key · payload 를 비교해 같으면 그대로(`ALREADY_PRESENT`), 다르면 `ORDER_EVENT_CONFLICT`(409). 이미 `PUBLISHED` 인 행을 `PENDING` 으로 되돌리지 않는다.
- 확정되지 않은 주문은 `ORDER_NOT_CONFIRMED`(409)로 거절한다. `confirmedAt` 이 없는 확정 주문은 사실이 부족한 것이라 추측하지 않고 실패한다.
- PG · 재고 · 결제를 건드리지 않는다(`RCV-002`).
- **관리자 API 로 두는 이유**는 requirements Q-2 에 있다.

## 5. 재개 (`RCV-004`)

`BLOCKED` 는 원인(record 크기 · topic 설정 등)을 고친 뒤 같은 행을 다시 보낸다.

```
java -jar commerce-api.jar … --outbox.resume.event-ids=order-confirmed:v1:41,order-confirmed:v1:42
```

- 기동 때 ApplicationRunner 가 각 `eventId` 를 `BLOCKED → PENDING`, `next_attempt_at = utc_timestamp(6)` 으로 바꾸고 결과를 로그로 남긴다. `attempt_count` · `last_error` 는 그대로 둔다 — 몇 번 실패한 행이었는지가 남는다.
- 보내는 일은 relay 가 한다. 재개는 상태만 바꾸므로 relay 와 실행 흐름이 겹치지 않는다. relay 가 꺼진 인스턴스에서 재개하면 relay 를 켠 인스턴스가 보낸다.
- `BLOCKED` 가 아닌 `eventId`(없음 · `PENDING` · `PUBLISHED`)는 바꾸지 않고 로그로 알린다. 기동은 막지 않는다 — 재개 대상 오타로 앱이 뜨지 않으면 다른 기능까지 멈춘다.
- 진입점이 기동 인자인 이유는 requirements Q-1 에 있다.

## 6. 검증

### 6.1 원자성 · 중단 (`EXP-010` ~ `EXP-012`)

| 장면 | 테스트 |
|---|---|
| 같은 TX 기록 · DB 시각 | `OrderEventOutboxAtomicityTest.confirmAppendsPendingOutbox` |
| 같은 거래 재반영에 한 행 | `OrderEventOutboxAtomicityTest.repeatedSuccessKeepsOneRow` |
| outbox insert 실패 → 확정 rollback | `OrderEventOutboxAtomicityTest.outboxFailureRollsBackConfirmation` — 같은 `eventId` 행을 미리 넣어 실제 유일 제약 위반을 만든다 |
| commit 뒤 relay 전 → relay 실행 | `OrderEventFlowE2ETest.confirmationReachesTopicOnlyThroughRelay` |
| broker 확인 뒤 기록 전 중단 → 새 relay | `OrderEventRelayTest.resendsAfterStopBeforeRecord` |

**중단은 "보내고 기록하지 않음" 으로 만든다.** relay 의 회차는 `sendDue`(고르기 · 보내기 · 대기)와 `record`(결과 TX)로 나뉘어 있어,
테스트는 `sendDue` 만 부르고 버린 뒤 새 relay 객체로 회차를 돌린다. 행을 직접 덮어쓰지 않고, 프로세스 강제 종료라고 부르지 않는다(`EXP-012`).

### 6.2 주문부터 구매 활동까지 (`EXP-013`)

commerce-api 의 JUnit 하나가 실행을 소유한다.

1. 공유 MySQL · Kafka 컨테이너에 실행 전용 topic · group 을 만든다.
2. streamer `bootJar` 를 **자식 JVM** 으로 띄운다 — 같은 MySQL · broker, 그 topic · group. 준비는 actuator health 로 기다린다.
3. 기존 주문 API 와 PG fixture 로 두 품목(수량 2 · 1) 주문을 확정한다.
4. relay 의 `sendDue` 만 부르고 버린다 — broker 확인 뒤 기록 전 중단.
5. 새 relay 로 회차를 돌린다 — 같은 행이 다시 나가고 `PUBLISHED`.
6. broker 를 직접 읽어 같은 `eventId` record 2 개(다른 offset)를, DB 에서 처리 기록 1 · 구매 활동 2 행(2 · 1) · 재고 · 결제 불변을 확인한다.
7. 자식 JVM · topic 을 정리한다.

- `test` task 가 `:apps:commerce-streamer:bootJar` 에 의존하고 jar 경로를 system property 로 넘긴다. 제품 runtime 의존은 생기지 않는다.
- streamer 의 DB 테이블은 공유 스키마에 streamer 가 만든다(W6 Q-2). 판정은 이 실행의 `eventId` · `orderId` 로 거른다 — 다른 테스트가 남긴 행과 섞이지 않게.

### 6.3 W6 누락 재현 → 복구 (`EXP-014`)

`docs/week7/recovery/run.sh` 한 실행. W6 `docs/week6/gap/run.sh` 의 구성(실행 전용 MySQL 컨테이너 · compose broker · 프로세스로 띄운 두 앱과 PG fixture · `kcat` 관찰)을 그대로 쓴다.

1. `upstream/seeun0210`(W6) 을 git worktree 로 받아 api · streamer `bootJar` 를 만든다. W7 jar 도 만든다.
2. W6 api · W6 streamer · PG fixture 를 띄운다.
3. **B**: 주문을 확정하고 구매 활동이 생길 때까지 기다린다.
4. **A**: W6 api 를 `linger.ms=60000` 으로 다시 띄워 확정하고, 확정이 commit 된 뒤 record 가 producer 버퍼에 있는 동안 `kill -9` 한다(흔적 없는 누락, W6 Q-6 의 뒤집을 조건).
5. 두 주문의 사실 · PG 거래 식별자 · PG create 누적 C · 외부 거래 수 T 를 기록한다. PG fixture 의 제어 포트에 호출 수 조회를 더한다.
6. W7 api 를 **같은 DB** 에 `ddl-auto=update` 로 띄운다 — outbox 테이블만 더해지고 기존 행은 그대로다. relay 를 켠다. streamer 는 W6 그대로 둔다(소비 계약이 같다).
7. 관리자 API 로 A · B 를 복구한다. relay 가 보낸다.
8. A 의 구매 활동 생성, B 의 활동 불변, 두 번째 복구 불변, PG create = C · 조회 · callback 증가 0 · 외부 거래 = T 를 확인하고 7 장의 운영 SQL 결과와 함께 `docs/week7/recovery/result.md` 에 쓴다.
9. 자신이 만든 프로세스 · 컨테이너 · topic · worktree 만 지운다.

## 7. 운영 절차 (`OPS-*`)

### 7.1 무엇을 보는가

```sql
-- 상태별 건수 · 가장 오래 기다린 시각 · 시도 수
select status, count(*) rows_, min(created_at) oldest_created, max(attempt_count) max_attempts
from order_event_outbox group by status;

-- 시각이 된 PENDING 중 가장 오래된 것의 나이(초) — relay 정지 · backoff 누적 · 처리량 부족
select timestampdiff(second, min(created_at), utc_timestamp(6)) oldest_pending_age_s
from order_event_outbox where status = 'PENDING';

-- 반복 실패 중인 PENDING — 같은 원인이 계속되는가
select event_id, attempt_count, next_attempt_at, last_error
from order_event_outbox where status = 'PENDING' and attempt_count > 0
order by created_at limit 20;

-- 미해결 BLOCKED
select event_id, order_id, attempt_count, last_error, updated_at
from order_event_outbox where status = 'BLOCKED' order by id;

-- PUBLISHED 인데 소비 처리 기록이 없는 사건 (1 분 넘게) — PUBLISHED 는 소비 완료가 아니다(OPS-002)
select o.event_id, o.published_at
from order_event_outbox o
left join order_confirmation_receipt r on r.event_id = o.event_id
where o.status = 'PUBLISHED' and r.event_id is null
  and o.published_at < utc_timestamp(6) - interval 1 minute;
```

발행 성공 · 실패 수는 W6 의 지표 `order.event.publish{result, cause}` 로도 본다. 마지막 쿼리는 운영 확인용으로만 streamer 의 테이블을 읽는다 — 코드가 상대 테이블을 읽지 않는다는 W6 Q-2 는 그대로다.

### 7.2 상태별 대처

| 상태 | 대처 |
|---|---|
| `PENDING` 이 쌓이고 `last_error` 가 broker 연결 · timeout | 조치 없음. broker 가 돌아오면 최대 1 분 안에 같은 행이 나간다 |
| `PENDING` 에 같은 `last_error` 가 반복(연결 외) | 원인(권한 · 설정)을 고치면 같은 행이 나간다. payload 를 고치지 않는다 |
| 가장 오래된 `PENDING` 이 오래되는데 `attempt_count` 가 0 | relay 가 돌지 않는다. `outbox.relay.enabled` 를 켠 인스턴스가 떠 있는지 본다 |
| `BLOCKED` | 원인(record 크기 · topic)을 고친 뒤 5 장의 재개 |
| `PUBLISHED` 인데 처리 기록 없음 | consumer lag · 보류(`order_event_hold`) · 멈춘 listener 를 본다(W6 4.6). outbox 를 되돌리지 않는다 |
| 확정 주문인데 outbox 행이 없음 | 4 장의 복구 |

### 7.3 보존 (`OPS-003`)

자동 삭제는 두지 않는다. `PENDING` · `BLOCKED` 는 아직 전달되지 않은 사실이라 지우면 그 사건을 다시 찾을 근거가 사라진다.

정리를 정할 때 지킬 관계:

- **소비 쪽 처리 기록(`order_confirmation_receipt`)은 같은 사건이 다시 올 수 있는 기간보다 오래 남아야 한다.** 먼저 지우면 재발행이 두 번째 효과를 만든다.
- 같은 사건이 다시 올 수 있는 경로는 셋이다 — topic 에 남은 record 의 재전달(broker 기본 보존, 바꾸지 않았다), `PUBLISHED` 전 중단 뒤 relay 의 재발행, **복구 API**.
- 복구 API 는 주문 행에서 사건을 언제든 다시 만든다. 그래서 `PUBLISHED` 행을 지우면 그 주문은 "outbox 가 없는 확정 주문" 이 되고, 복구가 같은 사건을 다시 보낼 수 있다.
  **`PUBLISHED` 행을 지우려면 처리 기록을 영구 보존하거나, 복구가 처리 기록 보존 기간보다 오래된 주문을 거절해야 한다.** 이 선택은 outbox 행을 사실로 볼지 캐시로 볼지와 같은 질문이다(`beyond-outbox.md` 2 장).

## 8. 알려진 한계 · 지금 고르지 않은 선택지

| 한계 | 지금 | 고를 수 있는 선택지 |
|---|---|---|
| 같은 주문의 사건 순서 | 행마다 따로 재시도해, 앞 행이 backoff · `BLOCKED` 이면 뒤 행이 먼저 나간다. 지금 사건은 `OrderConfirmed` 하나라 드러나지 않는다 | key 별 head-of-line · 소비 쪽 version 판정(`beyond-outbox.md` 1 장) · CDC 의 위치 하나로 꺼내기(`cdc-experiment.md` 6 장) |
| relay 한 대 | 그 인스턴스가 멈추면 발행이 멈춘다. 확정은 계속된다 | 다중 relay + 소유권(3.1) |
| 복제 장애 | 단일 broker 라 `acks=all` 이 복제 장애 내구성을 보이지 않는다 | W6 의 broker 3 대 실측(`docs/week6/cluster/result.md`) |
| polling 비용 | 1 초마다 인덱스 범위 조회 | CDC(`cdc-experiment.md`) |
| 직렬화 실패 | 구조로만 보장(2.3) | — |

## 9. 작업 단계

| # | 할 일 | 규칙 | 상태 |
|---|---|---|---|
| 1 | 같은 TX 기록 · rollback | `OBX-*` · `EXP-010` | 완료 |
| 2 | 단일 relay · 결과 분류 · backoff | `RLY-*` · `EXP-011` · `EXP-012` | 완료 |
| 3 | 복구 관리자 API | `RCV-001` ~ `RCV-003` | 완료 |
| 4 | `BLOCKED` 재개 | `RCV-004` | |
| 5 | rollback 뒤 재고 · PG create 확인 보강 | `OBX-007` · `EXP-010` | |
| 6 | 주문부터 구매 활동까지 | `EXP-013` | |
| 7 | W6 누락 재현 → 복구 · 운영 SQL 실행 결과 | `EXP-014` · `OPS-001` | |

# 7주차 Outbox 운영 문서 (C-9 · C-10)

> 주문 확정 이벤트(`OrderConfirmed`)의 `order_outbox`와 단일 relay를 **어떻게 보고, 막히면 어떻게 풀고, 무엇을 지우지 않는가.**
> 설계 근거는 `plan.md`, 실행 결과는 `incident-w7.md`. 이 문서의 SQL은 `docs/week7/ops/`에 있고 `bash docs/week7/ops/verify.sh`가 새 MySQL에서 상태별 데이터로 실행해 확인한다 (결과 `ops/results/`).

## 0. 상태의 뜻 — "보냈다"와 "처리됐다"는 다른 테이블에 있다

| 무엇 | 어디에 | 뜻 |
|---|---|---|
| `PENDING` | `order_outbox` | 아직 broker 확인(ack)이 없다. `next_attempt_at`이 지나면 relay가 고른다 |
| `SENDING` | `order_outbox` | relay가 맡아서 보내는 중. `next_attempt_at` = **lease 기한**(맡은 시각 + 1분). 기한이 지나면 다시 고른다 |
| `PUBLISHED` | `order_outbox` | broker가 받았다(ack, `published_partition`·`published_offset`). **consumer가 처리했다는 뜻이 아니다** |
| `BLOCKED` | `order_outbox` | 3번 연속 실패. 자동 재시도를 멈췄다 — 사람이 원인을 고치고 재개한다 (보내는 쪽의 DLT) |
| **소비 완료** | `consumed_order_confirmation` (commerce-streamer) | consumer가 구매 활동까지 저장했다. eventId unique — 같은 eventId가 다시 와도 no-op |

- 시각 컬럼은 **UTC**로 저장된다(`jpa.yml` `hibernate.jdbc.time_zone: UTC`). SQL에서는 `NOW()`가 아니라 `UTC_TIMESTAMP(6)`과 비교한다.
- commerce-api와 commerce-streamer가 같은 DB(`loopers`)를 쓴다는 전제다. O-6·O-8은 두 앱의 테이블을 함께 본다.

## 1. 관찰

### 1-1. SQL (`ops/observe.sql`)

| ID | 묻는 것 | 정상 | 이상하면 |
|---|---|---|---|
| O-1 | 상태별 개수, 가장 오래된 행 | PENDING·SENDING 수가 작고 금방 줄어든다 | 2-1 |
| O-2 | **backlog** — 지금 보낼 차례인데 아직 안 나간 행 수, 가장 오래 기다린 초 | 0~수 건, 대기 몇 초 | 계속 늘면 2-1, relay가 안 돌면 2-4 |
| O-3 | 재시도 중 (실패한 적 있고 BLOCKED 전) | 없음 | broker 쪽 오류인지 `last_error`로 본다 |
| O-4 | **BLOCKED 목록** | 없음 | 2-2 재개 |
| O-5 | lease가 지난 SENDING | 잠깐 생겼다가 다음 회차에 사라진다 | 몇 분 넘게 남으면 relay가 안 도는 것 → 2-4 |
| O-6 | **PUBLISHED인데 소비 완료가 없다** (발행 뒤 1분 넘음) | 없음 | 2-3 consumer 쪽 |
| O-7 | relay 작업 잠금(ShedLock) | `locked_at`이 계속 갱신된다 | 갱신이 멈추면 2-4 |
| O-8 | 한 사건 추적 — 주문 → Outbox → broker 위치 → 소비 완료 → 구매 활동 | — | 문의·장애 조사용 |

`verify.sh`로 확인한 결과 (`ops/results/w7-ops-20261106-122055.txt`). 상태별로 행 11개를 넣고 실행했다:

| ID | 넣은 상태 | 나온 결과 |
|---|---|---|
| O-2 | 차례가 된 PENDING(e-01) · lease 지난 SENDING(e-04) · 8일 된 PENDING(e-11) / 차례 전 PENDING(e-02) · lease 안 SENDING(e-03) | **3건**(e-01·e-04·e-11), 가장 오래 기다린 691,200초 = 8일. 차례 전 두 건은 빠짐. 실행 계획 `range` · `idx_order_outbox_due` · `Using index` |
| O-3 | attempt 1·2인 PENDING | e-02, e-11 |
| O-4 | BLOCKED 2건 | e-05, e-10 (`last_error` 포함) |
| O-5 | lease 3분 지난 SENDING, lease 안 SENDING | e-04만 (180초 지남) |
| O-6 | PUBLISHED+소비 완료(e-06) · 5분째 미소비(e-07) · 2초 전 발행(e-08) | **e-07만**. 막 발행된 e-08은 정상이라 빠짐 |
| O-7 | 다른 호스트가 잠금 보유 | `held_now = 1` |
| O-8 | e-06 | PUBLISHED · p0/offset 10 · consumed 1 · 활동 `7x2,8x1` |

### 1-2. 로그

| 로그 | 레벨 | 뜻 |
|---|---|---|
| `[OUTBOX_BLOCKED] …번 실패해 자동 재시도를 멈췄습니다 … eventId=, orderId=, cause=` | ERROR | BLOCKED가 됐다. **알림 대상** (2-2) |
| `[OUTBOX_RESUMED] BLOCKED 행을 다시 보낼 차례로 돌렸습니다 … eventId=, orderId=, lastError=` | WARN | 누군가 재개했다 (감사 기록) |
| `send 실패 — …에 다시 보냅니다. eventId=, attempt=, cause=` | WARN | 재시도 대기에 들어갔다 |
| `broker는 받았지만(ack) PUBLISHED를 저장하지 못했습니다 …` | ERROR | DB 저장 실패. 행은 SENDING에 남고 lease 뒤 **같은 eventId로 다시 보낸다** — consumer가 거른다 |
| `relay 회차: 고름 N · PUBLISHED M · 멈춘 행 X` | INFO | 고른 행이 있었던 회차만 남긴다(빈 회차는 로그 없음) |

### 1-3. relay 주기

- `@Scheduled(fixedDelay = 1s)` — **앞 회차가 끝난 뒤** 1초. 회차가 길면 간격도 늘고, 한 앱 안에서는 회차가 겹치지 않는다.
- 실측 (`OrderOutboxRelayTickIntegrationTest`, 실제 Spring scheduler): 회차가 바로 끝날 때 시작 간격 1.019 · 1.024 · 1.026초, 회차가 1.5초 걸릴 때 2.531 · 2.539초.
- 이 테스트는 `fixedRate`로 바꾸면 실패한다: 밀린 회차를 따라잡느라 0.009초 간격으로 몰아서 돌고, 1.5초 회차의 시작 간격이 1.54초가 된다. tick을 3초로 바꿔도 실패한다.
- 처리량 실측 (`live/measure-relay.py`, broker 3대 · acks=all · 로컬 Docker MySQL):

  | | 직렬 (처음) | 묶어 보내기 + 꽉 찬 회차면 이어 돌기 (11-06) |
  |---|---|---|
  | 2,000행 burst를 비우는 시간 | 35.7초 (초당 56건) | 17.9초 (**초당 112건**) |
  | 초당 100건 × 30초 유입 — backlog 최대 · 가장 오래 기다린 행 | 1,119행 · 15.1초 | **351행 · 3.0초** |
  | 초당 300건 × 30초 유입 | 6,990행 · 92.9초 | 4,200행 · 23.3초 |
  | 보낼 행이 없을 때 relay의 DB 비용 | 초당 약 9문장 · 7.7ms | 같음 |

- 주기 1초가 정하는 것은 **한가할 때의 지연(최대 1초)과 한가할 때의 DB 비용**이다. 처리량은 주기가 아니라 회차 안의 처리 방식이 정한다. 남은 비용은 행마다 DB TX 2개(맡기 · 기록)다.

## 2. 실패 대응

### 2-1. backlog가 늘어난다 (O-1·O-2)

1. O-3에서 `last_error`를 본다. broker 연결 오류(`TimeoutException`, `not present in metadata`)면 broker·topic 상태를 먼저 고친다. 고치면 relay가 알아서 이어 보낸다.
2. 오류가 없는데 늘기만 하면 relay가 피크를 못 따라가는 것이다. 직렬 처리의 한계라 묶음 크기만 키워서는 풀리지 않는다 (6절).

### 2-2. BLOCKED를 재개한다 (O-4) — 3절

### 2-3. PUBLISHED인데 소비 완료가 없다 (O-6)

- broker까지는 갔다. relay가 아니라 **consumer 쪽 문제**다. relay를 재개하지 않는다.
- streamer 로그와 consumer lag, DLT(`order-events.DLT`)를 본다. O-8의 `p`·`off`로 broker의 그 record를 찾는다.
- DLT 재처리 절차는 이번 주에 만들지 않았다 (6절 TODO).

### 2-4. relay가 돌지 않는다 (O-5가 계속 남음 · O-7 `locked_at` 갱신 멈춤)

1. 앱이 떠 있는지, `outbox.relay.scheduler.enabled`가 `true`인지 확인한다 (test 프로필은 `false`).
2. 잠금을 쥔 앱이 죽었으면 `lockAtMostFor` 5분 뒤 저절로 풀린다. 기다릴 수 없으면 3-2.

## 3. 재개 방법

### 3-1. BLOCKED 재개

1. O-4와 `[OUTBOX_BLOCKED]` 로그로 eventId와 원인을 확인한다.
2. **원인을 먼저 고친다.** 고치지 않고 재개하면 3번 더 실패하고 다시 BLOCKED가 된다.
3. 관리 포트의 actuator를 호출한다. local·test 프로필에서만 노출되고 공개 API(`/api`, `/api-admin`)에는 없다.
   ```bash
   curl -i -X POST http://127.0.0.1:8081/actuator/outbox/{eventId}
   ```
   | 응답 | 뜻 | DB |
   |---|---|---|
   | 200 | 재개함 | `BLOCKED → PENDING`, attempt 0, `next_attempt_at = 지금`, `last_error`는 남김 |
   | 404 | 없는 eventId | 변화 없음 |
   | 409 | BLOCKED가 아님 (예: 이미 PUBLISHED) | 변화 없음 |
4. 재개는 행을 PENDING으로 돌리기만 한다. **보내는 것은 다음 relay 회차(1초 안)다.** O-8로 PUBLISHED·소비 완료까지 확인한다.

근거 테스트: `OrderOutboxEndpointIntegrationTest`가 실제 관리 포트로 HTTP를 보내 확인한다.
- `resumesBlockedRow`: 200, PENDING, attempt 0
- `resumedRowIsSentByNextRelayRound`: 다음 회차에 같은 payload로 PUBLISHED
- `rejectsNonBlockedRow`: 409, 값 그대로
- `unknownEventIdIsNotFound`: 404

### 3-2. relay 잠금 강제 해제 (`ops/release-lock.sql`)

- 잠금을 쥔 앱이 **확실히 죽었을 때만** 쓴다. 살아 있는 relay와 겹쳐 같은 행을 두 번 보낼 수 있다. 그래도 consumer가 거르지만 broker record는 하나 더 생긴다.
- `UPDATE shedlock SET lock_until = UTC_TIMESTAMP(3) WHERE name = 'order-outbox-relay';`
- **행을 DELETE하지 않는다.** ShedLock은 한 번 본 잠금 행에는 UPDATE만 하므로, 앱이 도는 중에 행을 지우면 재시작할 때까지 잠금을 다시 얻지 못한다(relay 정지). 4단계 테스트를 짜다가 실제로 겪었다.
- `verify.sh` 확인: 실행 전 `held_now = 1`, 실행 뒤 `0`.

## 4. 보존 — 미발행 사실은 지우지 않는다

| 대상 | 보존 | 지우는 조건 |
|---|---|---|
| `order_outbox` PUBLISHED | 7일 | `published_at`이 7일 지남 |
| `order_outbox` PENDING · SENDING · BLOCKED | **지우지 않는다** | 없음. 미해결 사실이다 — 재개하거나 원인을 밝힌다 |
| Kafka `order-events` | 7일 (retention) | broker가 지운다 |
| `consumed_order_confirmation` | **Outbox·Kafka 보존보다 짧으면 안 된다** | 먼저 지우면 그 뒤 재발행이 처음 보는 eventId로 처리돼 구매 활동이 두 번 쌓인다 |

- 삭제 job은 만들지 않았다. 수동 SQL `ops/retention.sql`은 조건에 `status = 'PUBLISHED'`가 있어 다른 상태를 건드리지 않는다. 1,000행씩 영향 행 0이 될 때까지 반복한다.
- `verify.sh` 확인: 8일 된 PUBLISHED(e-09) · BLOCKED(e-10) · PENDING(e-11) 가운데 **e-09 한 건만 삭제**됐다. e-10과 e-11은 남았다.

## 5. 배포·전환 순서

1. `docs/week7/schema.sql`의 `order_outbox`·`shedlock` 테이블을 먼저 만든다. 문자셋과 collation은 적지 않았다 — DB 기본값을 따라 다른 테이블과 JOIN할 수 있게 한다.
2. W7 앱을 배포한다. 이때부터 확정 TX는 `order_outbox`에만 쓴다.
3. W6 앱이 모두 내려간 뒤 `event_outbox`를 지운다(`DROP TABLE`). W6 미발행 행은 옮기지 않기로 했다(학습자 결정, 11-06).
   - 옮겨야 하는 경우의 방법은 C-7에서 확인했다: 테스트 전용 복구 도구가 eventId와 payload 원문을 그대로 옮기고, 같은 eventId가 있으면 건너뛴다.
- topic 이름을 바꿀 때: topic은 행이 아니라 설정에서 읽으므로, **PENDING·SENDING·BLOCKED가 0인지 O-1로 확인한 뒤** 설정을 바꾼다. 남은 행은 새 topic으로 간다.

## 6. 알려진 한계 (C-10)

| 한계 | 영향 | 지금의 대응 · 다음 단계 |
|---|---|---|
| **relay는 하나다** (ShedLock) | 앱이 여러 대여도 한 번에 한 대만 보낸다. 실측 상한 초당 약 112건(묶어 보내기 뒤) | 맡기 · 기록 TX를 묶음 단위로 합치면 더 오른다(대신 저장 실패가 묶음 전체에 퍼진다). 여러 relay로 나누면 같은 key의 순서가 깨진다 — key 단위 분배나 CDC 단일 task가 필요하다 |
| 과제 문구는 "ack 뒤 저장 전 중단 → **PENDING** 유지"이지만 이 설계에서는 **SENDING**으로 남는다 | lease(1분)가 지나야 다시 보낸다 — 최대 1분 늦다 | PR에 적는다. 다시 보내는 결과(같은 eventId 재발행, consumer no-op)는 같다 (C-5·C-6) |
| broker는 eventId 중복을 걸러 주지 않는다 | 재발행마다 broker에 record가 하나 더 생긴다 (C-6: offset 2·3) | consumer의 eventId 완료 기록이 계속 책임진다 |
| 행별 backoff는 같은 주문 이벤트의 순서를 바꿀 수 있다 | 지금은 주문당 OrderConfirmed 하나라 영향 없음 | 이벤트 종류가 늘면: 같은 order_id의 앞선 미발행 행이 있으면 뒤 행을 고르지 않음 + consumer version 비교 (`plan.md` 한계) |
| 실패 종류를 나누지 않는다 | 잠깐 멈춘 broker 때문에도 약 2분 뒤 BLOCKED가 될 수 있다 | `[OUTBOX_BLOCKED]` ERROR 로그와 O-4로 감지한다. **로그 기반 알림(Slack 등)은 아직 없다** |
| 재개 진입점이 운영 프로필에 없다 | local·test에서만 쓸 수 있다 (과제: 공개 관리자 API로 노출하지 않음) | 운영에서는 내부망 관리 포트 노출과 권한을 따로 정해야 한다 |
| DLT 재처리 | consumer가 DLT로 보낸 메시지를 다시 처리하는 절차가 없다 | TODO (학습자, 11-04) |
| 늦게 도착한 구매 활동 | BLOCKED 등으로 늦게 나간 행은 이미 닫은 랭킹 구간에 빠질 수 있다 | 랭킹 쪽 유예 시간·재계산 (범위 밖) |
| Outbox 테이블 증가 | 조회는 인덱스로 차례가 된 행만 읽어 영향이 없다 (O-2 실행 계획). 저장 공간은 는다 | 삭제 job은 다음 단계. 파티셔닝은 `event_id` unique와 충돌해 보류 |
| CDC(Debezium)를 쓰지 않았다 | polling 주기·tick 겹침·lease를 직접 관리한다 | 기술 글에서 비교. CDC여도 consumer 중복 처리와 DLT는 남는다 |

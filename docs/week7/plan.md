# 주문 확정 이벤트의 Transactional Outbox와 단일 relay (W7)

> **이 문서의 자리** — W7의 Plan·Design 산출물이다. W6 `docs/week6/plan.md`(발행 기록 `event_outbox`, ADR-W6-01~06)에서 이어진다.
>
> **목표** — 주문 확정 TX에 같이 남긴 OrderConfirmed Outbox 행을 단일 polling relay가 broker에 보낸다. commit 뒤 발행 전에 멈추거나, broker 확인 뒤 상태 저장 전에 멈춰도 같은 eventId로 다시 보내고, W6 consumer의 멱등성으로 구매 활동은 한 번만 남긴다.
>
> **범위 밖** (과제) — 별도 결제수단 · 새 주문 도메인 · 범용 saga · CDC 플랫폼 · 다중 relay.
>
> **표기** — ✅ 합의 · 🔧 AI 기본값 · 🤔 결정 필요.
>
> **진행 방식** — 설계 항목을 하나씩 학습자와 정한다. 아직 다루지 않은 항목은 비워 둔다. 역할은 아래 "작업 방식"을 따른다.

## 0. 체크리스트 — 모두 통과해야 한다 (노션 과제 원문 요약)

| # | 체크리스트 | 증거 | 상태 (11-03) |
|---|---|---|---|
| C-1 | 최초 PG 확정·예약 소비·Outbox insert가 같은 TX에서 commit/rollback한다 | W6 `OrderConfirmedPublishIntegrationTest`(C-1·`rollbackPublishesNothing`). 테이블을 `order_outbox`로 바꾼 뒤 다시 확인 | 🔧 W6 통과, 테이블 교체 뒤 재확인 |
| C-2 | 중복 결과 적용·실패·UNKNOWN에서 거짓 이벤트나 추가 결제가 생기지 않는다 | `$NoEvent`(PENDING·FAILED·rollback·중복 적용·이미 CONFIRMED) + **`$Unknown` 4개(11-03 추가)** | ✅ 14/0/0. `$Unknown`은 버그 3종(M1~M3)을 일부러 넣어 실패함을 확인 |
| C-3 | 기존 주문의 rollback 결과를 신규 주문 삭제와 혼동하지 않는다 | `rollbackPublishesNothing` — 주문 PAYMENT_PENDING 유지 | ✅ W6 통과 |
| C-4 | 단일 relay가 저장된 identity·payload를 그대로 보내며 broker 확인 후 PUBLISHED로 바꾼다 | | 🤔 |
| C-5 | commit 후 relay 전 중단과 broker 확인 후 상태 저장 전 중단을 검증했다 | | 🤔 |
| C-6 | 실제 broker·consumer에서 재발행돼도 상품별 구매 효과가 늘지 않는다 | | 🤔 |
| C-7 | 새 격리 DB에서 W6 누락·소비 완료 상태를 재현하고 같은 실행의 사건으로 복구했다 | | 🤔 |
| C-8 | 준비와 복구의 PG 호출 수를 나눠 기록했고 복구 중 PG 추가 호출·주문 재확정·예약 추가 소비가 없다 | | 🤔 |
| C-9 | PENDING·PUBLISHED·소비 완료를 구분하고 실패·backlog·재개 방법을 확인했다 | | 🤔 |
| C-10 | 미발행 사실을 삭제하지 않으며 단일 relay·보존·재시도의 한계를 설명한다 | | 🤔 |
| C-11 | 관련 회귀·lint·ArchUnit을 실행하고 자신이 만든 테스트 자원만 정리했다 | | 🤔 |
| T-1 | 기술 글 — Outbox가 새로 보장한 것과 소비자가 계속 책임지는 것 | | 🤔 |
| P-1 | PR — 같은 eventId의 주문→Outbox→broker→구매 활동 결과, 단일 relay 제약, 미해결 오류의 재개 방법 | | 🤔 |

## 작업 방식 ✅ (11-03)

- 순서 ✅: 0 테이블 재설계 → 1 relay + 결과 기록 주체 → 2 after-commit 직접 발행 제거 → 3 재시도·BLOCKED → 4 relay 실행 방식(겹침 방지) → 5 중단 실험 두 개 → 6 누락 재현·복구·PG 호출 수 → 7 E2E → 8 운영(관찰·재개 진입점·보존·한계 문서) → 9 회귀 → 기술 글 → PR.
  - relay 생성과 "결과 기록 주체"를 한 단계로 합쳤다 — 기록 주체가 relay의 모양 자체라서 나누면 두 번 짠다.
  - 직접 발행 제거를 relay 뒤로 미뤘다 — 먼저 지우면 relay 전까지 발행이 0이 되고 W6 테스트가 깨진 채 남는다.
- 역할 ✅ (C안): 핵심 로직은 학습자가 `volume-7`(`../loop-pack-be-l2-vol5-java-v7`)에서 직접 짜고, Claude는 리뷰한다. 테이블·설정·어댑터·테스트 초안·문서는 Claude가 `volume-7-claude`(`../loop-pack-be-l2-vol5-java-claude`, push 안 함)에서 쓰고 학습자가 cherry-pick한다.
- 핵심 로직 (학습자 담당, 테스트는 엄격히 본다):
  1. `OrderOutbox` 도메인의 상태 전이 — 어떤 상태에서 어디로만 가나, 거절 뒤 기존 값 유지
  2. relay 한 회차 — 고르기 → send → future 대기 → 결과(성공·명시적 실패·timeout·저장 실패)별 기록
  3. backoff·BLOCKED 판단 — 시간을 고정한 리터럴 기대값
  4. 겹침 방지 — tick과 수동 실행이 동시에 들어올 때 send는 한 번

## 이미 정해진 전제 (W6·멘토링에서)

- ✅ **발행 위치는 commit 뒤** (멘토링 11-02) — DB가 SoT다. TX 안에서 외부 호출(Kafka send)을 하면 그 실패가 SoT와의 어긋남을 만든다. TX 안에 들어가는 것은 Outbox **insert**(같은 DB 쓰기)이고, send는 W7에서도 TX 밖(relay)이다.
- ✅ **Outbox insert는 확정과 같은 TX** — `OutboxAppendService`의 `MANDATORY` 유지. `REQUIRES_NEW`·after-commit insert는 쓰지 않는다.
- ✅ **eventId는 고정** (ADR-W6-01) — 최초 확정 TX에서 UUID를 한 번 만들어 행에 저장하고, 모든 재발행이 그 값을 읽는다. 시도마다 새로 만들면 W6 consumer가 같은 주문·다른 eventId를 충돌로 보고 DLT로 보낸다.

## D-0. Outbox 테이블 재설계

### 테이블을 나눈다 ✅ (학습자, 11-02)

- W6의 범용 `event_outbox`를 주문 전용 `order_outbox`로 바꾼다. 이유(학습자): 나중에는 이벤트별로 책임이 나뉘어야 한다. 대가: 이벤트 종류가 늘면 테이블·relay도 는다.
- 필드는 강의 자료의 저장 필드를 따른다 ✅ (학습자, 11-02) — 재시도 횟수·스키마 버전 등이 필요하다.

### 컬럼

| 강의 자료 필드 | 컬럼 | 결정 |
|---|---|---|
| eventId | `event_id` UUID, 유일 제약 | ✅ (W6 그대로) |
| orderId | `order_id` (W6 `aggregate_id`) | ✅ 주문 전용이라 `aggregate_type`은 뺀다 — 모든 행이 ORDER라 정보가 없다 |
| messageKey | `message_key` (주문 ID 문자열) | ✅ (W6 그대로) |
| eventType | `event_type` | ✅ (W6 그대로) |
| schemaVersion | `schema_version` | ✅ 추가 (지금은 payload 안에만 있다) |
| payload | `payload` TEXT 원문, 생성 뒤 변경 없음 | ✅ (W6 그대로) |
| status | `PENDING` · `SENDING` · `PUBLISHED` · `BLOCKED` | ✅ SENDING 포함 (학습자, 11-03). 아래 "SENDING을 둔다" |
| attemptCount · nextAttemptAt | `attempt_count` · `next_attempt_at` | ✅ 추가 (의미·갱신 시점은 D-1·D-3에서) |
| createdAt · lastError · publishedAt | `created_at` · `last_error` · `published_at` | ✅ (W6 그대로) |
| — | `published_partition` · `published_offset` | ✅ 남긴다 (학습자, 11-04) — broker 응답의 위치. P-1(같은 eventId의 주문→Outbox→broker→구매 활동)과 C-9의 증거, 장애 때 record 찾기. 재발행되면 마지막 값만 남는다 |
| — | `topic` | ✅ **뺀다 — relay가 설정 `kafka.topics.order-events`에서 읽는다** (학습자, 11-03) |

### 인덱스

- ✅ relay 조회용 인덱스가 필요하다 (학습자, 11-02). 🔧 `(status, next_attempt_at, id)` (11-04) — 조회 조건 `status IN ('PENDING','SENDING') AND next_attempt_at <= :now ORDER BY id LIMIT :n`(D-1). 차례가 된 행만 범위로 읽으므로 쌓인 PUBLISHED는 비용에 영향이 없다. IN 두 값 때문에 `ORDER BY id`는 정렬이 남지만 대상이 차례가 된 행뿐이라 작다.
- 🔧 `event_id` unique 유지 (W6), `order_id` 인덱스 (주문별 조회·같은 주문 순서 조건이 생길 때).

### topic은 설정에서 읽는다 ✅ (학습자, 11-03)

| 선택지 | 판단 |
|---|---|
| 도메인(비즈니스 로직)에서 생성 | ❌ 도메인이 전송 수단(인프라)을 알게 된다. 환경별 이름(dev·prod)을 못 나눈다. 오타가 컴파일을 통과한다 |
| 행에 저장 | △ 범용 테이블(행마다 목적지가 다름)에는 맞다. 전용 테이블에서는 모든 행이 같은 값이라 중복이고, topic 이름을 바꾸면 쌓인 PENDING이 아무도 안 읽는 옛 topic으로 간다 |
| **설정에서 읽기** | ⭕ 고정할 것(eventId·key·payload·schemaVersion = 무엇을 보냈나)과 바뀔 수 있는 것(topic = 어디로 보내나)을 나눈다 |

- 대가: 옛 행을 꼭 옛 topic으로 보내야 하면, topic을 바꾸기 전에 PENDING을 0으로 비운 뒤 설정을 바꾼다 (운영 문서에 적는다). 어느 topic으로 갔는지는 행만으로 모른다.

### SENDING을 둔다 ✅ (학습자, 11-03)

- 비교한 안: A `PENDING·PUBLISHED·BLOCKED`(강의 자료) / **B A + `SENDING`** / C status 없이 시각 컬럼의 null 여부.
- PENDING과 SENDING은 "broker 확인을 아직 저장하지 못했다"는 점에서 같다. 다른 점은 **어떤 실행자가 맡아서 보내는 중인가**뿐이다. 단일 relay에서는 이 표시가 막아 줄 다른 실행자가 없다.
- 비용: send 도중 프로세스가 죽으면 행이 SENDING에 남는다. PENDING만 고르는 relay는 그 행을 다시 보내지 않는다 → 막으려던 누락이 상태 이름만 바뀌어 생긴다. **만료(lease) 규칙이 반드시 함께 필요하다** — 🤔 아래.
- 과제 문구와 다른 점 (PR에 적는다): 과제는 "broker 확인 뒤 PUBLISHED 저장 전 중단 → 같은 Outbox **PENDING** 유지, 재시작 후 재발행"을 기대한다. 이 설계에서는 그 행이 **SENDING**으로 남고, 만료 뒤 재발행된다.
- 선택 이유 (학습자): 상태를 더 명확히 드러낸다 — 대기 중(PENDING)과 보내는 중(SENDING)을 구분한다. 큰 비중을 둔 결정은 아니다.
- 만료 표현 ✅ (학습자, 11-03): **`next_attempt_at`을 lease로 함께 쓴다.** 도메인 메서드가 SENDING으로 바꿀 때 `next_attempt_at = now + lease`를 정한다. relay는 "`PENDING` 또는 `SENDING`이고 `next_attempt_at ≤ now`"를 고른다 — 재시도 대상과 만료된 SENDING을 한 조건으로 잡는다. 대가: 한 컬럼이 "재시도 시각"과 "맡은 기한" 두 뜻을 갖는다. lease 길이는 🔧 1분 — relay 대기 35초(D-1)보다 길게 정했다. 대기 시간보다 짧으면 보내는 중인 행을 다시 고른다.
- 🔧 lease의 책임 나눔 (11-04): **규칙은 도메인**(`OrderOutbox`가 SENDING으로 바꾸며 `next_attempt_at = now + lease`를 정한다) · **저장은 DB**(행의 `next_attempt_at`, 프로세스가 죽어도 남는다) · **만료 판단은 relay 조회 조건**(`next_attempt_at ≤ :now`) · **현재 시각은 앱이 주입한 `Clock`**(DB `NOW()`가 아니다 — 테스트에서 시각을 고정하고, 단일 relay라 서버 간 시계 차이가 없다). 앱 메모리에만 두면 kill과 함께 사라지므로 DB에 둔다.

## 핵심 1. `OrderOutbox` 상태 전이 기대값 ✅ (11-04, 학습자가 TDD로 구현)

| 메서드 | 허용되는 시작 상태 | 결과 |
|---|---|---|
| `pending(...)` (Claude 작성) | — | PENDING, attempt 0, `next_attempt_at = now`, `message_key = orderId` |
| `startSending(now)` | PENDING, 기한이 지난 SENDING | SENDING, `next_attempt_at = now + 1분`(lease) |
| `markPublished(partition, offset, now)` | SENDING | PUBLISHED, `published_at·partition·offset` 기록 |
| `recordFailure(error, now, delay)` | SENDING | attempt+1. 3이면 BLOCKED, 아니면 PENDING + `next_attempt_at = now + delay`. `last_error`는 1000자로 자른다 |
| `resume(now)` | BLOCKED | PENDING, attempt 0, `next_attempt_at = now`, `last_error` 유지 |
| 그 밖의 조합 (예: PUBLISHED → SENDING, PENDING에 성공 기록) | — | **예외**, 기존 값 유지 |

- 🔧 backoff 지연(jitter 난수)은 `OrderOutbox` 밖의 정책 객체가 계산해 `delay`로 넘긴다 — 도메인 테스트에 난수가 들어오지 않는다 (핵심 3).
- 🤔 학습자가 정할 것: 예외 타입, "기한이 지난 SENDING"을 도메인이 `now`로 직접 확인할지(조회 조건만 믿을지).

## D-3. 재시도·BLOCKED (일부 먼저 정함)

- ✅ 재시도 간격 (학습자, 11-03): 지수 backoff + 상한 + **full jitter**. 난수는 주입받아 테스트에서 고정한다. 학습자 메모 — 단일 relay에서는 큰 효과가 없다(직렬이라 몰림이 적다), 넣어도 된다.
- ✅ **숫자: 기본 10초 · 배수 3 · 상한 5분** (학습자, 11-04). n번째 실패 뒤 대기 = random(0, min(5분, 10초 × 3^(n-1))) → 1차 0~10초, 2차 0~30초, 3차는 BLOCKED. 장애가 이어지면 BLOCKED까지 약 2분(대기 35초 × 3 + backoff). 1분 안팎의 broker 재시작은 넘길 여지가 있고, 실패 감지(ERROR 로그)도 늦지 않다.
  - 비교한 안: 1초·배수 2(약 1분 50초, 짧은 장애에도 BLOCKED가 쏟아짐) / 1분·배수 5(6~8분, 감지가 늦음).
- ✅ **BLOCKED가 보내는 쪽의 DLT다** (11-04). 학습자 제안 "BLOCKED를 DLT로"를 검토했다 — DLT도 Kafka라 broker 장애로 BLOCKED가 된 행은 DLT로도 못 보내고, 7일 retention 뒤 사라지며, 다시 보내는 도구가 따로 필요하다. DB(SoT)에 BLOCKED로 두면 SQL로 찾고, 재개 진입점이 PENDING으로 돌리면 relay가 보낸다. Kafka DLT는 받는 쪽 실패용이다(한계의 TODO).
- ✅ **재개하면 attempt를 0으로 되돌린다** (학습자, 11-04) — 원인을 고쳤다는 전제로 다시 3번의 기회. `last_error`는 지우지 않고 남긴다.
- ✅ **잘못된 상태에서의 전이는 예외로 거절한다** (학습자, 11-04) — 예: PUBLISHED 행 재개, SENDING이 아닌 행에 성공 기록, 겹친 회차의 늦은 기록. 정상 흐름에서는 일어나지 않는 일이라 조용히 넘기면 버그가 숨는다. relay는 한 행에서 예외가 나면 ERROR 로그를 남기고 회차를 멈춘다. W5 `StockReservation`의 boolean과 다른 이유: 그쪽은 같은 PG 결과가 정상적으로 여러 번 온다.
- ✅ **3번 실패하면 BLOCKED** (학습자, 11-03).
- ✅ 실패 종류를 나누지 않는다 (학습자, 11-03) — broker 일시 장애도 3번이면 BLOCKED. 감수하는 비용: 잠깐 멈춘 broker 때문에도 BLOCKED가 생기고, 사람이 감지해 재개해야 한다. 그래서 BLOCKED를 **감지하는 수단**이 필요하다 (학습자: admin·Slack 알림 등 모니터링). 🔧 이번 범위: BLOCKED 전이 때 eventId·orderId·원인을 담은 ERROR 구조화 로그 + BLOCKED 목록 SQL + 로컬 전용 재개 진입점(공개 관리자 API로 노출하지 않음 — 과제). Slack 알림은 로그 기반 알림으로 다음 단계(한계 절).

## D-1. relay 한 회차 (일부 먼저 정함)

- ✅ **한 회차** (11-03): relay를 한 번 호출해서 끝날 때까지 — scheduler tick 한 번 또는 수동 재개 한 번.
  1. 보낼 차례가 된 행(PENDING이거나 만료된 SENDING, `next_attempt_at ≤ now`)을 `id` 순서로 최대 N개 고른다
  2. 한 행씩 차례로: SENDING(+lease) → send → broker 응답 대기 → 결과 기록
  3. 묶음을 다 처리하면 끝
  - 근거: 노션 과제 3절(작은 묶음·안정된 순서·직렬 실행), 4절(한 번의 처리 개수, "relay 한 회차"), 강의 ⚖️절(한 번에 한 루프).
- 🔧 **묶음 크기 N = 200, tick 간격 = 1초** (11-04, 측정 뒤 확정). N은 한 회차가 고르는 최대 행 수(`LIMIT N`)이고 처리량이 아니다.
  - 역산: `N ≥ 피크 확정 수 × tick 간격 × 여유` = 100건/s(W5 `plan.md` 0-1 부하 가정 — k6 실측은 없음) × 1s × 2 = 200.
  - 처리량(건/s)의 상한 = min(N ÷ tick 간격, 1 ÷ 행 하나 처리 시간). 직렬이라 행 하나가 10ms면 회차가 아무리 커도 100건/s를 넘지 못한다.
- ✅ **행은 한 번에 하나씩 직렬로 처리한다** (A안, 학습자, 11-04) — send → ack 대기 → 기록을 끝낸 뒤 다음 행. 단순하고 "실패하면 멈춤"이 그대로 맞는다.
  - 비교한 안 B: N개를 id 순서로 모두 send한 뒤 future를 한꺼번에 기다린다. Kafka가 내부에서 묶어 보내 처리량이 훨씬 크지만, 실패가 나도 뒤 행은 이미 보낸 뒤라 "멈춤"의 뜻이 바뀌고 핵심 로직이 복잡해진다.
- ✅ **broker 응답 대기 — 실패는 빨리 난다** (B안, 학습자, 11-04). 원칙: relay 대기 > producer가 포기하는 시간(`delivery.timeout.ms`). 그래야 relay가 실패로 적은 행은 producer도 정말 포기한 행이고, 늦게 성공하는 사본이 버퍼에 쌓이지 않는다.
  - 🔧 `delivery.timeout.ms` 30초 (기본 120초에서 줄임. Kafka 규칙상 `request.timeout.ms` 20초 + `linger.ms` 이상) · relay의 future 대기 35초 · lease 1분 (대기보다 길어야 보내는 중인 행을 다시 고르지 않는다).
  - 🔧 설정 위치: 공용 `modules/kafka/kafka.yml`은 streamer(DLT 발행)도 쓰므로, commerce-api 설정에서만 덮어쓴다.
  - 비교한 안: A 기본값 120초 유지(장애 때 회차가 2분 멈춤) / C 대기만 5초(relay가 실패로 적은 뒤 producer가 늦게 성공해 재시도와 겹치는 사본이 생김).
  - 🤔 확인할 것: relay를 만든 뒤 100 TPS로 행 하나 처리 시간을 실측한다. 직렬로 피크를 못 따라가면(backlog가 계속 늘면) B로 바꾸고 그 측정을 기술 글에 쓴다.
- ✅ **회차 도중 한 행이 실패하면 그 회차를 멈춘다** (학습자, 11-04). 실패한 행은 attempt를 올리고 `next_attempt_at`을 backoff만큼 미루므로, 다음 회차는 그 행을 건너뛰고 뒤 행부터 고른다 — 늦어지는 것은 실패한 한 건뿐이다.

| | 실패하면 회차를 멈춤 (채택) | 실패해도 다음 행으로 계속 |
|---|---|---|
| broker가 멈췄을 때 | 1건만 대기 시간(timeout)을 쓰고 끝난다 | N건 모두 timeout까지 기다린다. 회차 하나가 길어지고 attempt_count도 N건 모두 오른다 (3번이면 BLOCKED가 대량으로 생긴다) |
| 행 하나만 문제일 때 (예: payload 이상) | 나머지가 다음 tick에 나간다 | 나머지가 바로 나간다 |
| 맞는 경우 | 실패 원인이 주로 broker 쪽일 때 | 실패 원인이 주로 행 쪽일 때 |

  - 선택 이유: payload는 우리가 직렬화해 저장한 JSON이라 행 하나만 깨질 일은 드물고, 실패는 주로 broker 연결 문제다. 대가: 행 하나만 문제일 때 나머지가 tick 한 번만큼 늦게 나간다.
  - 비교한 안이 아닌 것: "실패한 행이 성공할 때까지 뒤 행을 보내지 않는다"(전체 head-of-line blocking)는 쓰지 않는다. 앞 행이 BLOCKED가 되면 모든 주문의 구매 활동이 멈춘다.

### 순서 — 무엇 사이의 순서인가 ✅ (11-04)

| 순서 | 필요한가 | 이번 주 |
|---|---|---|
| **다른 주문끼리** (먼저 확정된 주문 vs 나중 주문) | 필요 없다. 서로 독립된 사실이고, 확정 시각은 payload의 `confirmedAt`이 갖는다 | relay에서 순서가 바뀌어도 구매 활동 결과는 같다 |
| **같은 주문 안의 이벤트끼리** (확정 → 취소 등) | 필요하다. 취소가 확정보다 먼저 처리되면 틀린 결과가 남는다 | 주문당 이벤트가 OrderConfirmed 하나라 깨질 순서가 없다 |

- 한정 수량 상품에서 먼저 주문한 사용자가 손해를 보지 않는가 (학습자 질문, 11-04): **보지 않는다.** 누가 재고를 갖는지는 Outbox가 아니라 commerce-api DB에서 이미 정해진다 — 결제 요청 때 재고를 예약하고(`PaymentRequestService` → `stockReservationUseCase.reserve`), 결제 성공 TX에서 소비, 실패 때만 해제한다. 시간 만료로 풀리지 않는다. relay가 늦게 보내는 것은 "확정됐다"는 **소식**(구매 활동 → 랭킹 입력)이지 주문의 결과가 아니다.
  - 먼저 주문한 사용자의 결제가 PG 재시도·UNKNOWN 중이어도 예약은 유지되므로, 뒤 주문이 그 재고를 가져가지 못한다. 이것은 W5 재고 예약의 책임이고 W7 relay의 책임이 아니다.
  - 남는 영향은 늦게 도착한 구매 활동이 이미 닫힌 랭킹 구간에 빠지는 것(late-arriving data)이다. 이번 주에는 랭킹이 없어 한계로만 적는다.

## 보존 정책 ✅ (학습자, 11-02 — 문서는 8단계에서)

- Outbox 행과 Kafka retention 모두 7일.
- 🔧 Outbox에서 7일 뒤 지우는 대상은 PUBLISHED만이다. PENDING·BLOCKED는 미해결이라 자동 삭제하지 않는다 (과제). 이번 과제에서는 삭제 작업 자체를 만들지 않는다.
- 🔧 consumer의 중복 방지 기록(`consumed_order_confirmation`)은 Outbox·Kafka 보존보다 짧으면 안 된다 — 먼저 지워지면 그 뒤 재발행이 처음 보는 eventId로 처리돼 구매 활동이 두 번 쌓인다.

## 알려진 한계 (PR에 적는다)

- 🔧 행별 backoff는 같은 key의 순서를 깨뜨릴 수 있다 — 앞 행이 재시도 대기 중이면 뒤 행이 먼저 나간다. 이번 이벤트는 주문당 OrderConfirmed 하나라 영향이 없다. 이벤트 종류(취소·보류 등)가 늘면 세 겹으로 지킨다: ① Kafka — `message_key = orderId`라 같은 주문은 같은 파티션, 파티션 안은 보낸 순서 ② relay — "같은 order_id에 아직 PUBLISHED가 아닌 앞선 행이 있으면 뒤 행을 고르지 않는다"(같은 주문끼리만 head-of-line, 다른 주문은 막지 않음; 앞 행이 BLOCKED면 그 주문의 뒤 이벤트도 사람이 풀 때까지 멈춤) ③ consumer — version·시각 비교로 오래된 이벤트 무시.
- 🔧 늦게 도착한 구매 활동(late-arriving data) — 실패·BLOCKED로 늦게 나간 행은 이미 계산해 닫은 랭킹 구간에 빠질 수 있다. 랭킹 쪽에서 닫기 전 유예 시간 또는 재계산으로 푼다. 이번 주 범위 밖.
- ✅ **TODO: DLT 재처리** (학습자, 11-04 — 시간이 남으면 다룬다). consumer가 DLT로 보낸 메시지를 누가 언제 어떻게 다시 처리할지 이번 주에는 정하지 않는다. 이번 주는 보내는 쪽(relay)만 다룬다.
- 🔧 broker는 eventId로 중복을 걸러 주지 않는다 — 멱등 producer는 같은 send의 내부 재시도만 걸러 낸다(producerId·sequence). relay 재발행은 새 record이고, eventId 중복 처리는 consumer(`consumed_order_confirmation` unique)가 계속 책임진다.
- 🔧 Outbox 테이블 증가 — relay는 인덱스로 차례가 된 행만 읽으므로 쌓인 PUBLISHED가 조회 비용을 키우지 않는다. 삭제 job은 다음 단계. `created_at` 범위 파티셔닝(파티션 DROP = 삭제)은 MySQL이 모든 unique 키에 파티션 컬럼을 요구해 `event_id` unique와 충돌하므로 이번 주에는 하지 않는다.
- 🔧 CDC(Debezium)로 바꾸면 polling 주기·batch·tick 겹침·여러 relay의 순서·SENDING/lease가 인프라로 옮겨 간다. consumer 중복 처리(Debezium도 at-least-once), consumer 실패와 DLT, 커넥터 장애 때 전체 정지, Kafka Connect 운영 비용은 남는다. 기술 글의 대안 비교에 쓴다.
- 🔧 여러 relay에서는 행 단위 claim(`SKIP LOCKED`)이 같은 key의 순서를 깨뜨린다. 순서를 지키는 방식은 "같은 key는 한 실행자가 순서대로"(ShedLock으로 한 대만 / key 단위 분배 / CDC 단일 task)뿐이고, 재발행 때문에 consumer version 비교가 마지막 방어선이다.

## 변경 이력

- 11-03: 문서 시작. 체크리스트·작업 방식·전제·D-0(테이블 분리, 컬럼, topic은 설정)·보존 정책·한계를 적었다. C-2에 UNKNOWN 테스트 4개 추가.
- 11-03: D-0에 SENDING·lease(`next_attempt_at`) 추가, D-3(backoff + jitter, 3번 실패 → BLOCKED, 감지 수단), D-1 한 회차 정의.
- 11-04: D-1 "실패하면 회차를 멈춘다"와 비교표, 순서 구분(다른 주문 vs 같은 주문)·한정 수량 공정성 답. 한계에 같은 주문 순서 세 겹·late-arriving data·DLT 재처리 TODO·broker 중복·테이블 증가·CDC 비교 추가. partition·offset 남김 ✅, 인덱스 🔧(D-0으로 옮김), backoff 숫자 ✅, lease 책임 나눔 🔧, BLOCKED = 보내는 쪽 DLT·재개 시 attempt 0·잘못된 전이는 예외 ✅, 핵심 1 기대값 표. 0단계 구현: order_outbox 엔티티 뼈대·포트·어댑터·schema.sql, 조회 테스트 4개. N=200·tick 1초(🔧), 직렬 처리 ✅, 대기(`delivery.timeout.ms` 30s · 대기 35s · lease 1분) ✅/🔧.

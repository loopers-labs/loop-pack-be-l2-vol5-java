# Runbook: health 는 UP 인데 결제가 "확인 중"에 머문다 (PG 성공 응답 유실)

> 근거: 로컬 통제 실험 `CheckoutIncidentTest` (Testcontainers MySQL·Redis·Kafka, HTTP 가짜 PG).
> 증거: [`docs/week10/evidence/20261125T074911Z-9bacc715/`](../week10/evidence/20261125T074911Z-9bacc715/manifest.json)
> 운영 배포·실계정·실제 PG·부하에서는 검증하지 않았다. 아래 절차는 이 코드베이스의 실제 API·테이블·로그 이름을 쓴다.

## 0. 한 줄 요약

PG 는 결제를 성공시켰는데 생성 응답을 잃으면, 주문은 **결제 대기(`AWAITING_PAYMENT`) + 거래 키 없음**으로 남는다. 프로세스·DB·Kafka 는 멀쩡하므로 health 는 UP 이다.
**PG 에 기존 거래를 조회해 검증한 SUCCESS 만** 기존 결과 반영 경계로 적용한다. 새 결제를 만들거나 DB 를 직접 고치지 않는다.

## 1. 증상

| 관찰 | 값 |
|---|---|
| 고객 화면 | 주문이 "결제 확인 중" (`status = AWAITING_PAYMENT`) |
| `GET /actuator/health` (management 포트) | `UP` — 의존 서비스가 내려가지 않았기 때문. **health 로는 이 사고를 볼 수 없다** |
| `commerce_payment_awaiting{pg_transaction="missing"}` | 0 보다 큼 (실험: 3) |
| `commerce_payment_awaiting{pg_transaction="known"}` | 이 사고만으로는 늘지 않음 (실험: 0) |

지표 정의 (`PaymentAwaitingMetrics`):

- `known`: 결제 대기이면서 PG 거래 키가 있음 — PG 가 PENDING 으로 접수하고 최종 결과(callback)를 기다리는 중.
- `missing`: 결제 대기이면서 거래 키가 없음 — **PG 결과를 모름(UNKNOWN)**, 또는 결제 시작 직후 PG 응답을 기다리는 짧은 순간.
- DB 를 `commerce.metrics.payment-awaiting.refresh-interval`(기본 5s)마다 센 값이다. **최대 그 주기만큼 늦다.** 갱신 실패·첫 갱신 전은 `NaN`(0 과 다름).
- 두 태그는 **같은 scrape 에서** 함께 본다. 실험에서 `missing` 이 3 이 된 scrape 의 `known` 이 4 였다: 정상 주문이 PENDING 접수 뒤 확정되기 전 시점이었다. 한 태그만 보고 판단하지 않는다.
- 태그에 주문·사용자 ID 는 없다. 개별 주문은 아래 질의와 로그로 찾는다.

### 운영 설정에서 이 증상이 보이는 조건 (중요)

운영 기본값은 `pg.lookup-delay: 6s`(백업 거래 조회), `pg.payment-expiry: 9s` · `pg.payment-expiry-interval: 1s`(결제 대기 만료)다.

| 상황 | 결과 |
|---|---|
| 응답만 잃고 PG 조회는 정상 | 6초 뒤 백업 조회가 SUCCESS 를 반영해 **스스로 복구**. `missing` 이 잠깐 올랐다 내려간다 |
| 응답도 잃고 백업 조회도 실패 (PG 조회 장애·네트워크) | 9초 뒤 만료 job 이 **FAILED + 재고 반환**. PG 에 SUCCESS 가 있으면 **결제됐는데 주문 실패**. 이 runbook 의 조치로는 복구할 수 없다 → §5 중단 조건 |
| 만료 job 이 돌지 않음 (스케줄러 정지 등) | 결제 대기가 오래 남는다 → 이 runbook 의 대상 |

실험은 원인을 보려고 백업 조회·만료를 test 프로필 값(1h)으로 관찰 동안 보류했다. 제품 기능을 지우지 않았다.

## 2. 진단 질의 (모두 읽기 전용)

### 2-1. 미확인 결제 대기 목록

```sql
-- 결제 대기 + 거래 키 없음. payment_started_at 이 오래된 순
select id, user_id, payment_attempt_key, total_amount, payment_started_at
from orders
where status = 'AWAITING_PAYMENT' and transaction_key is null
order by payment_started_at;
```

### 2-2. 주문별 예약·Outbox

```sql
select order_id, product_id, type, quantity from product_stock_ledger where order_id in (:ids) order by id;   -- RESERVE 만 있어야 한다
select order_id, event_id, status, attempt_count from order_event_outbox where order_id in (:ids);             -- 확정 전이므로 0 행
```

### 2-3. 경계 로그 (구조화 key-value, `OrderFacade`·`PgClient`)

| 찾는 것 | 조건 |
|---|---|
| 생성 요청 결과 | `event=payment.request.result`, `orderId=:id` → `outcome`, `transactionKey`, `elapsedMs` |
| 응답 처리 | logger `PgClient`, 메시지 `PG 응답 본문 없음, 결과 모름 [orderId = :id]` (또는 `PG 응답 처리 실패`) |
| 결과 반영 TX | `event=payment.apply`, `orderId=:id` → `source`, `outcome`, `tx`(`COMMITTED`/`ROLLED_BACK`), `error` |
| 거래 조회 | `event=payment.lookup.result`, `orderId=:id` → `source`(`RESOLVE`/`CALLBACK`), `outcome` |

로그 파일은 접근이 제한된 곳에서만 본다. 카드번호는 로그에 남지 않는다(마스킹).

### 2-4. PG 거래 조회 (권위 조회, 읽기만)

```
GET {pg.base-url}/api/v1/payments?orderId=ORDER-{orderId}
X-USER-ID: {주문의 user_id}
```

확인: 거래 **1건**, `status`, `transactionKey`, `amount == orders.total_amount`, 같은 사용자.
이 조회는 commerce 상태를 바꾸지 않는다. **commerce 의 resolve API 는 진단에 쓰지 않는다** — 결과를 적용한다.

## 3. 경쟁 가설과 판정

조치 전에 주문마다 적는다. **PG SUCCESS + 결제 대기 조합만으로는 판정하지 않는다.**

| 가설 | 예상 관찰 | 확인 |
|---|---|---|
| H1 PG 처리 자체가 지연 | 권위 조회 `PENDING` | 2-4 |
| H2 PG 성공 응답 유실 | 권위 조회 `SUCCESS` · 주문 결제 대기 + 거래 키 없음 · `payment.request.result outcome=UNKNOWN` · `PgClient` 본문 없음/처리 실패 · `payment.apply` 에 `ROLLED_BACK` 없음 | 2-1, 2-3, 2-4 |
| H3 성공 결과의 내부 반영 실패 | 권위 조회 `SUCCESS` · `payment.apply tx=ROLLED_BACK` (+`error`) · 예약 그대로 · Outbox 없음 | 2-2, 2-3 |

- 위 기록 중 하나라도 없거나 어긋나면 **미확정**으로 두고 조치하지 않는다.
- 실험 판정: 주문 13·16·19 모두 H2 (`03-diagnosis.json` 의 `facts`·`verdict`). 진단 전후 주문 행이 같았다(`stateUnchanged: true`).
- AI 에게 원문 기록과 가설을 주고 예상·실제를 비교하게 할 수 있다. 판정과 상태 변경 조치는 사람이 범위·중단 조건을 확인한 뒤 한다.

## 4. 조치

### 전제 (모두 만족해야 시작)

1. 영향 주문 목록을 **고정**했다 (2-1 결과의 ID 목록). 진행 중 새로 생기는 주문은 섞지 않는다.
2. 각 주문의 판정이 H2 이고, 2-4 로 **거래 1건·SUCCESS·금액 일치·같은 사용자**를 확인했다.
3. 응답 유실 원인(PG·네트워크 경로)이 해소됐거나, 해소되지 않아도 조회 경로는 정상임을 확인했다.
4. 주문이 아직 `AWAITING_PAYMENT` 다 (만료 job 이 FAILED 로 바꾸지 않았다).

### 실행 — 기존 본인 주문 결제 대기 해소 API

```
POST /api/v1/orders/{orderId}/payment/resolve-awaiting-payment
X-USER-ID: {주문의 user_id}
```

- 하는 일: 본인 주문 확인 → PG 에 **기존 거래 조회** → 동기 응답·callback 과 같은 `applyResult` 로 반영. **PG create 를 부르지 않는다.**
- 반영 규칙: SUCCESS 이고 금액이 주문 합계와 같을 때만 `CONFIRMED` + Outbox 1행(같은 TX). 거래 키가 이미 있는데 다르면 무시. PENDING·UNKNOWN 은 상태 유지.
- 한 주문씩 실행하고, 응답 `status` 와 §6 확인을 거친 뒤 다음 주문으로 간다.
- 다른 사용자 헤더로 부르면 `403 NOT_OWNER` 이고 PG 를 부르지 않는다. 관리자용 대리 조정 API 는 없다 (남은 위험 §8).

### 하지 않는 것

- `update orders set status = 'CONFIRMED' ...` 같은 **DB 직접 변경**
- 같은 주문에 새 `paymentAttemptKey` 로 `confirm` 재호출, PG `POST /api/v1/payments` 재호출 (**재결제**)
- 재고·Outbox·구매 활동·랭킹 snapshot 직접 수정

## 5. 중단 조건

다음 중 하나면 그 주문은 **멈추고 상태를 보존**한 뒤 에스컬레이션한다. 임의로 성공·실패로 바꾸지 않는다.

| 관찰 | 의미 | 할 일 |
|---|---|---|
| 권위 조회 실패·404·timeout (`payment.lookup.result outcome=UNKNOWN`) | PG 결과를 모름 | 유한 횟수(예: 3회, 간격 두고) 뒤 중단 |
| 거래 0건 또는 복수 | 계약 위반·다른 경로 결제 의심 | 중단, PG 측 확인 |
| 금액 불일치 (로그 `PG 결제 금액 불일치, 확정 보류`) | 반영 규칙이 확정을 거절 | 중단 |
| 계속 `PENDING` | H1 | 중단하고 PG 처리 대기 (callback·재조회) |
| `FAILED` 거래 | 결제 실패 | resolve 가 FAILED + 재고 반환으로 반영한다. 기대와 다르면 중단 |
| `payment.apply tx=ROLLED_BACK` 반복 | H3, 내부 반영 실패 | 중단, DB·Outbox 직렬화 원인 조사 |
| 주문이 이미 `FAILED` 인데 PG 는 `SUCCESS` | 만료 job 이 먼저 거둠 (§1) | **이 runbook 범위 밖**. 결제 취소·환불 절차가 없다. 사람 판단으로 처리 |
| 거래 키 불일치 (로그 `PG 거래 키 불일치, 결과 무시`) | 다른 거래 | 중단 |

## 6. 복구 확인 (새 조회로)

```sql
select id, status, transaction_key, paid_amount, confirmed_at from orders where id in (:ids);          -- 모두 CONFIRMED, 거래 키 = PG 거래 키, paid_amount = PG amount
select order_id, type from product_stock_ledger where order_id in (:ids);                               -- RESERVE 만 (추가 차감·반환 없음)
select order_id, event_id, status from order_event_outbox where order_id in (:ids);                     -- 주문당 1행
```

- PG 측: 같은 주문의 거래 수가 늘지 않았다 (create 추가 0).
- 지표: `commerce_payment_awaiting{pg_transaction="missing"}` 가 영향분만큼 줄었다 (갱신 주기만큼 기다린다).
- 반복 확인: 같은 주문에 resolve 를 다시 불러도 `CONFIRMED` 그대로, PG 를 부르지 않고, Outbox 행이 늘지 않는다. 늦은 callback 도 같다.
- 실험 결과: 장애 집합 같은 10개 ID 가 CONFIRMED 10·대기 0, PG create 20 → 20, 재고 25 → 5 유지, 포인트 불변.

### 후속 결과

- Outbox relay 가 발행하면 구매 활동이 주문당 품목 수만큼 한 번씩 생긴다. 발행 시도 수(`attempt_count`)와 활동 수를 혼동하지 않는다.
- 구매 활동의 `confirmed_at` 은 **복구 시점에 처음 확정된 시각**이다. PG 요청 시각으로 바꾸지 않는다. 그 값의 주에 집계된다.
- 그 주의 랭킹이 **이미 공개됐다면** snapshot 을 고치지 않고 W9 절차대로 새 revision 을 만든다. 실험에서는 같은 주 첫 snapshot(r1)에 baseline 10 + 복구 포함 10 = 점수 20 이 들어갔다.

## 7. 재진입 (중간에 멈췄다가 다시 시작)

1. 2-1 을 다시 실행해 **남은** 결제 대기 목록을 만든다. 이미 CONFIRMED 인 주문은 resolve 가 PG 를 부르지 않고 현재 상태를 돌려주므로 다시 불러도 안전하다.
2. §4 전제를 남은 주문마다 다시 확인한다 (그 사이 만료 job 이 FAILED 로 바꿨을 수 있다 → §5).
3. Outbox 가 `BLOCKED` 면 W7 절차대로 eventId 로 재개한다. `PENDING` 은 relay 가 다시 고른다.
4. 구매 활동은 eventId 로 중복 소비를 막으므로 재발행이 활동을 늘리지 않는다.

## 8. 남은 위험

| 위험 | 상태 |
|---|---|
| 백업 조회까지 실패하면 만료 job 이 PG SUCCESS 주문을 FAILED 로 만든다 | 결제 취소·환불 경로 없음. 지표로도 보이지 않는다 (결제 대기가 아님) |
| 생성 요청 read timeout·본문 수신 중 끊김은 `ERROR` → 즉시 FAILED | W5 정책. 이 실험의 UNKNOWN 경로(2xx 본문 해석 불가)와 다르다. 결제됐으면 위와 같은 위험 |
| 관리자 대리 조정 API 없음 | 운영자가 사용자 헤더로 호출해야 한다. 접근 통제·감사 기록이 필요하다 |
| prd JSON 로그(logstash)에서 key-value 가 최상위 필드로 나오는지 | 미확인. 로컬 테스트는 logback 이벤트의 key-value 를 직접 봤다 |
| 분산 trace | 구현하지 않았다. 주문 ID 기반 로그 상관관계만 있다 |
| 다중 인스턴스·부하 | 미검증. gauge 는 인스턴스마다 같은 DB 를 센다 (합산하면 중복) |

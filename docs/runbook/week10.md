# Runbook — 결제가 "확인 중"에 멈춘 사고

대상 증상: 사용자는 주문이 **결제 확인 중**으로 멈춰 있다고 말하는데 프로세스 `health`는 UP이다.

이 문서는 운영자가 위에서 아래로 실행하는 절차다. 각 절은 **무엇을 확인하고, 어떤 조건에서 멈추는지**를 함께 적는다.

> **검증 범위 경고.** 이 절차는 로컬 합성 실험(실행 `20261127-052251-d8d97825`)에서 확인한 것이다. 실제 운영 배포, 실계정, 실제 PG, 부하 상황에서 실행 검증한 것이 아니다. 숫자 임계값과 권한·보안 정책은 **아직 정해지지 않았고**, 이 문서는 그 자리를 비워 둔다. 임계값을 확정하려면 운영 트래픽의 측정 근거가 필요하다.

주소는 환경에 맞게 바꾼다. `<api>`는 업무 API, `<mgmt>`는 management 포트(이 저장소 기본 8081), `<pg>`는 PG 주소다.

---

## 1. 증상과 초기 탐지

다음 중 하나라도 보이면 이 절차로 들어온다.

- 사용자 문의: 주문이 결제 확인 중에서 더 진행되지 않는다.
- 주문 상태가 `PAYMENT_PENDING`으로 남아 있다.
- 결제 시도 상태가 `UNKNOWN` 또는 `PENDING`이다.
- `commerce_payment_unresolved{state="unknown"}`가 평소보다 올라갔다.

**health UP은 주문이 끝났다는 뜻이 아니다.** 이 사고의 핵심이 그것이다. 상태 검사는 설정한 의존성만 보고, 특정 주문의 종결은 보지 않는다. 실험에서 `UNKNOWN` 3건과 `{"status":"UP"}`가 같은 관측 시점에 공존했다.

지표 증가만으로 사고를 확정하지 않는다. 정상 결제 진행 중에도 `UNKNOWN`이 잠시 나타날 수 있다. 1건이라도 **오래 남아 있는 것**이 문제다.

---

## 2. 지표와 최신성 확인

```bash
curl --include --silent --show-error --connect-timeout 3 --max-time 10 '<mgmt>/actuator/health'
curl --silent --show-error --connect-timeout 3 --max-time 10 '<mgmt>/actuator/prometheus' \
  | grep '^commerce_payment_unresolved'
```

보이는 네 가지 지표의 뜻이다.

| 지표 | 뜻 |
| --- | --- |
| `commerce_payment_unresolved{state}` | 미종결 결제 시도 수. `state`는 `requested`, `pending`, `unknown` |
| `commerce_payment_unresolved_oldest_created_timestamp_seconds{state}` | 그 상태에서 가장 먼저 **생성된 시도**의 생성 시각(Unix epoch) |
| `commerce_payment_unresolved_refresh_last_success_timestamp_seconds` | 마지막 집계 성공 시각(Unix epoch) |
| `commerce_payment_unresolved_refresh_total{result}` | 집계 성공·실패 누적 횟수 |

**건수만 보지 않는다.** 다섯 가지 상태를 구분한다.

| 관측 | 해석 |
| --- | --- |
| 건수 0, 마지막 성공이 최근 | 실제로 미종결이 없다 |
| 건수 n, 마지막 성공이 최근 | 정상 갱신 중이고 n건이 실제 사실이다 |
| 건수가 멈춰 있고 `refresh_total{result="failure"}`가 증가 | 집계 실패. 건수는 **마지막 성공값**이다 |
| `time() - last_success`가 허용 범위를 넘음 | 스냅샷이 낡았다. 지표로 판단하지 않는다 |
| 네 지표가 모두 `NaN` | 아직 한 번도 집계하지 못했다. 0건이 아니다 |

PromQL로 보는 질의다.

```promql
# 마지막 집계 이후 경과 초
time() - commerce_payment_unresolved_refresh_last_success_timestamp_seconds

# 가장 오래된 미종결 시도의 나이(초)
time() - commerce_payment_unresolved_oldest_created_timestamp_seconds

# 최신 스냅샷을 가진 인스턴스만 남기고 최대값 (<freshness>는 운영에서 정한다)
max by (state) (
  commerce_payment_unresolved
  and on (instance)
  (time() - commerce_payment_unresolved_refresh_last_success_timestamp_seconds < <freshness>)
)
```

**정해지지 않은 것.** `<freshness>`와 "며칠 이상 남으면 대응" 같은 임계는 운영 기대와 측정 근거로 정해야 한다. 이 문서는 값을 정하지 않는다.

**알아야 할 지표 한계 세 가지.**

- 기준 시각은 `created_at`이다. "그 상태에 머문 시간"이 아니라 **결제 시도가 끝나지 않은 채 흐른 시간**이다. `updated_at`은 충돌 기록으로도 움직여 상태 진입 시각이 아니다.
- 지표는 DB 전체를 보는 **지연된 근사**다. 특정 주문을 찾는 데 쓰지 않는다.
- 인스턴스 N대가 같은 DB를 집계하므로 `sum`은 N배가 된다. `max`도 낡은 값이 남을 수 있어 정확한 건수는 DB 조회로 확인한다.

---

## 3. 영향 범위와 결제 식별자 확인

지표로는 대상을 찾을 수 없다. DB에서 목록을 뽑는다.

```sql
-- 미종결 결제 시도 목록 (영향 범위)
SELECT p.id AS attempt_id, p.order_id, p.user_id, p.attempt_key, p.provider_order_id,
       p.transaction_key, p.status, p.amount, p.created_at, p.updated_at,
       p.lookup_count, p.manual_check_required, p.conflicted, p.conflict_reason,
       o.status AS order_status, o.paid_amount, o.confirmed_at
FROM payment_attempts p JOIN orders o ON o.id = p.order_id
WHERE p.status IN ('REQUESTED', 'PENDING', 'UNKNOWN')
ORDER BY p.created_at;
```

여기서 대사에 필요한 식별자를 모은다. `order_id`, `attempt_key`, `provider_order_id`, `transaction_key`, `amount`, `user_id`다. `provider_order_id`는 `LP-ORD-%010d` 형식이라 `order_id`를 포함한다. 로그와 PG 조회가 이 값으로 이어진다.

**대상 집합을 고정한다.** 조치 전에 처리할 주문 ID를 목록으로 적어 둔다. 실행 중에 새 주문이 들어와 범위가 늘어나지 않게 한다.

---

## 4. 읽기 전용 대사

이 절은 **읽기만** 한다. 상태를 바꾸는 조치는 6절부터다.

### 4.1. 내부 상태

```sql
SET @order_id = <orderId>;

SELECT * FROM orders WHERE id = @order_id;
SELECT * FROM payment_attempts WHERE order_id = @order_id;
SELECT * FROM stock_reservations WHERE order_id = @order_id;
SELECT * FROM order_confirmed_outbox WHERE order_id = @order_id;
SELECT * FROM order_items WHERE order_id = @order_id;
```

### 4.2. PG 거래 (권위 조회)

```bash
curl --include --silent --show-error --connect-timeout 3 --max-time 30 --get \
  --header 'X-USER-ID: <payment.pg.user-id 설정값>' \
  --data-urlencode 'orderId=<provider_order_id>' \
  '<pg>/api/v1/payments'
```

응답 본문을 **원문으로 보존**한다. 그리고 내부 행과 네 항목을 맞춘다. 거래 수, `orderId`, `amount`, `transactionKey`다.

이 조회는 읽기 전용이고 commerce 상태를 바꾸지 않는다. 제품의 reconcile과 다르다. **증거를 보존하기 전에 reconcile을 호출하지 않는다.**

주의: 이 조회로 지금 SUCCESS가 보인다고 해서 "장애 당시 제품이 SUCCESS를 수신했다"는 뜻이 아니다. 그것은 지금 시점의 조회다.

### 4.3. 경계 로그

로그에서 같은 주문을 잇는다. `orderId`로 찾고, PG 경계는 `providerOrderId`로 찾는다. 한 HTTP 요청 안의 줄들은 trace id를 공유한다.

| 로그 | 답하는 질문 |
| --- | --- |
| `pg.create outcome=ACCEPTED\|REJECTED_NO_EFFECT\|UNKNOWN` | 거래가 수락됐는지, 어디서 결과를 잃었는지 |
| `payment.create outcome=... unknownRows=` | 어떤 주문이 미확인으로 기록됐는지 |
| `pg.lookup outcome=FOUND count= statuses=` | 권위 조회가 가능했는지, 거래가 몇 건인지 |
| `payment.apply phase=attempt storedStatus= targetStatus= settled=` | 내부 적용을 **시도**했는지 |
| `payment.apply phase=committed appliedStatus=` | 그 시도가 실제로 **커밋**됐는지 |
| `payment.apply phase=rolled-back` / `completion-unknown` | 되돌아갔는지, 결과를 알 수 없었는지 |
| `payment.reconcile` / `payment.callback` `decision=` | 복구 조치가 무엇을 보고 무엇을 했는지 |

**로그 해석 규칙.**

- `outcome=ACCEPTED`는 **거래 수락**이고 결제 성공이 아니다.
- `phase=committed`는 **트랜잭션 커밋**이고 결제 성공이 아니다. `appliedStatus=FAILED`인 커밋도 정상이다.
- `settled=0`은 **적용하지 않았다**는 사실만 뜻한다. 중복 적용이라고 단정하지 않는다. `storedStatus`는 그 트랜잭션이 시작할 때 읽은 값이고 현재 상태가 아니다.
- `phase=attempt`만 있고 `committed`가 없으면 **최종 결과 미확인**이다. 커밋 직후 프로세스 종료, 로그 유실, 수집 실패가 모두 같은 모습을 만든다. 반영 여부는 **DB 상태로 판정한다.**
- 로그는 DB 상태를 대체하지 않는다.

---

## 5. 경쟁 가설과 판정

조치 전에 세 가설을 적고 증거로 가른다.

| 가설 | 예상 관찰 | 가르는 증거 |
| --- | --- | --- |
| 1. PG가 아직 처리 중 | 권위 조회가 `PENDING` | 같은 거래의 현재 최종 상태 |
| 2. PG 성공 후 생성 응답 유실 | PG `SUCCESS`, 내부 `UNKNOWN`, `transaction_key` 없음, `pg.create outcome=UNKNOWN` | adapter 결과 + 내부 UNKNOWN 기록 + 적용 시도 부재 |
| 3. 성공 수신 후 내부 반영 실패 | PG `SUCCESS`, 적용 시도 기록, `rolled-back` 또는 `completion-unknown` | `payment.apply` 줄의 존재와 TX 결과 |

**PG SUCCESS와 내부 UNKNOWN 조합만으로 2와 3을 가르지 않는다.** 둘 다 그 조합을 만든다. 가르는 신호는 적용 시도의 유무다.

판정표다.

| 관찰 | 판정 | 다음 |
| --- | --- | --- |
| 내부 `SUCCESS`·주문 `CONFIRMED` | 이미 종결 | 조치 없이 9절 업무 완료 확인 |
| PG 단일 `SUCCESS` + 내부 `UNKNOWN` + `transaction_key` NULL + `pg.create outcome=UNKNOWN` + `payment.apply` 줄 없음 | 가설 2가 가장 지지됨 | 6절로 진행 |
| PG `SUCCESS` + `payment.apply phase=attempt` 있고 `committed` 없음 | 가설 3 또는 미확인 | **복구로 진행하지 않는다.** 롤백 원인과 예약·Outbox 상태를 조사한 뒤 재진단 |
| 권위 조회 `PENDING` | 가설 1 | 정한 관찰 한도 안에서 재조회. 계속 PENDING이면 PG 처리 지연을 조사 |
| 조회 실패·0건·복수 거래·금액 불일치 | 중단 | 7절 중단 조건 |
| 증거가 부족 | **미확정** | 빠진 기록을 확보한 뒤 재진단. 추측으로 확정하지 않는다 |

장애 주입 코드나 AI의 추측을 원인 확정 근거로 쓰지 않는다. 상태를 바꾸는 조치는 사람이 범위와 중단 조건을 확인한 뒤 수행한다.

---

## 6. 완화와 복구의 구분

둘은 다른 일이다.

| 구분 | 목적 | 주의 |
| --- | --- | --- |
| 완화 | 새 피해를 줄인다 | 이미 미확인인 주문은 그대로 남는다 |
| 복구 | 영향받은 업무를 올바른 상태로 되돌린다 | 권위·금액·주문 일치가 전제다 |

완화로 쓸 수 있는 것과 쓸 수 없는 것이다.

| 조치 | 효과 | 한계 |
| --- | --- | --- |
| 원인이 된 입력 중단 | 영향 범위 고정 | 기존 미확인은 남는다 |
| 같은 거래 조회 후 조정 | 기존 주문 종결 | 일치 검증이 전제 |
| 프로세스 재시작 | 일시적 실행 상태 회복 | **외부 거래는 없어지지 않는다** |
| 코드 rollback | 코드 회귀 완화 | **DB·PG 사실은 과거로 돌아가지 않는다** |

**재시작과 재결제를 먼저 하지 않는다.** 응답 timeout은 PG 거래가 없다는 뜻이 아니다. `UNKNOWN`에 create를 다시 보내면 중복 결제가 생길 수 있다.

### 복구 전 전제 확인

1. 5절에서 가설 2가 지지된 주문만 대상이다.
2. 대상 주문 목록이 고정돼 있다.
3. 조치 전 상태가 보존돼 있다(10절).
4. 장애 원인이 되는 입력이 멈췄다.
5. PG 거래가 단일하고 `amount`와 `orderId`가 내부 값과 같다.

하나라도 아니면 멈춘다.

**권한.** 이 API는 **주문 소유자 본인**만 조정할 수 있다. 다른 사용자의 주문으로 호출하면 404다. 실험에서 확인했다. 운영자가 사용자를 대신해 호출하는 경로와 그 권한 정책은 **이 저장소에 없다.** 운영에서는 별도로 정해야 한다.

---

## 7. 복구 절차

대상 주문 하나씩, 기존 reconcile API를 호출한다.

```bash
curl --include --silent --show-error --connect-timeout 3 --max-time 30 --request POST \
  --header 'X-USER-ID: <주문 소유자 ID>' \
  '<api>/api/v1/orders/<orderId>/payment/reconcile'
```

이 API는 **기존 거래를 다시 조회해 현재 결과를 반영**한다. 새 create를 보내지 않는다.

### 응답별 다음 행동

| 응답 | 뜻 | 다음 |
| --- | --- | --- |
| **200** + `orderStatus=CONFIRMED`, `attemptStatus=SUCCESS`, `lookupUnavailable=false` | 내부 확정됨 | 8절 정합성 확인 |
| **200** + `attemptStatus=SUCCESS`이고 이미 종결이던 주문 | 반복 호출. 같은 사실로 수렴 | 추가 조치 없음 |
| **200** + `orderStatus=PAYMENT_FAILED` | PG 실패가 반영됨. 예약이 반환될 수 있다 | **성공 복구로 처리하지 않는다.** 실패 원인과 반환 이력을 보존 |
| **202** + `lookupUnavailable=true` | 미확정. 조회 불가 또는 계속 `PENDING` | 상태 보존 후 **중단** |
| **5xx** | 권위 조회 실패 (callback 경로) | 상태 보존 후 중단 |
| **404** | 주문 없음 또는 **본인 주문 아님** | 소유자와 주문 ID 확인 |
| 통신 실패·시간 초과 | 결과를 받지 못함 | **처리 안 됐다고 단정하지 않는다.** 즉시 재호출하지 않고 4절부터 현재 상태를 다시 확인 |

### callback 경로의 계약

PG가 보내는 `POST /api/v1/payments/callback`은 본문의 `status`를 신뢰하지 않고 권위 조회로 확인한다.

| 응답 | 뜻 |
| --- | --- |
| 200 | 종결됨. 이미 종결이던 주문의 재전송도 200이고 사실이 늘지 않는다 |
| 202 | 아직 미종결 |
| **409** | 거래 불일치. 복수 거래, 거래 0건, `orderId` 불일치, `transactionKey` 불일치, **금액 불일치** |
| 5xx | 권위 조회 실패. 상태는 바뀌지 않는다 |

409를 받으면 `payment_attempts`의 `conflicted`, `conflict_reason`, `conflicted_at`과 대조하고 **중단한다.**

### 중단 조건

다음 중 하나라도 해당하면 상태를 보존하고 멈춘다. 임의로 성공으로 만들지 않는다.

- 권위 조회가 실패하거나 서킷이 열려 있다.
- 거래가 0건이다.
- 거래가 2건 이상이다(`MULTIPLE_TRANSACTIONS`).
- 금액이 내부 값과 다르다(`AMOUNT_MISMATCH`).
- `orderId`가 다르다(`ORDER_MISMATCH`).
- 저장된 `transaction_key`와 조회된 키가 다르다(`TRANSACTION_KEY_MISSING`).
- 계속 `PENDING`이다.
- 가설 3이 배제되지 않았다.

**절대 하지 않는 것.**

- DB를 직접 UPDATE해서 성공으로 만들기
- 새 PG create 호출(재결제)
- 성공 수를 맞추기 위한 상태 조작
- 사고 단계 사이에 DB나 PG 원장 초기화

---

## 8. 결제·재고·예약·Outbox 정합성 확인

복구 후 **같은 주문 집합**을 새로 조회해 확인한다. baseline이나 새 주문을 섞어 수를 채우지 않는다.

```sql
-- 상태와 효과를 함께 본다
SELECT p.status AS attempt_status, o.status AS order_status, o.paid_amount, o.confirmed_at,
       p.transaction_key, p.amount, r.status AS reservation_status, r.quantity
FROM payment_attempts p
JOIN orders o ON o.id = p.order_id
LEFT JOIN stock_reservations r ON r.order_id = p.order_id
WHERE p.order_id IN (<대상 주문 목록>);

-- 미종결이 남았는지
SELECT status, COUNT(*) FROM payment_attempts
WHERE status IN ('REQUESTED','PENDING','UNKNOWN') GROUP BY status;

-- 주문 1건마다 Outbox 1건이고 event_id 가 주문에서 유도되는지
SELECT order_id, event_id, status FROM order_confirmed_outbox
WHERE order_id IN (<대상 주문 목록>) ORDER BY order_id;

-- 상품 가용 재고
SELECT id, stock_quantity FROM products WHERE id IN (<상품 목록>);

-- 포인트·쿠폰
SELECT * FROM points WHERE user_id = <userId>;
SELECT * FROM coupon WHERE user_id = <userId>;
```

확인할 불변 조건이다.

| 항목 | 기대 |
| --- | --- |
| 적용된 거래 키 | 복구 전 PG가 보관한 키와 **동일** |
| PG create 호출 수 | 복구 전후 **증가 0** |
| PG 거래 집합 | 복구 전과 동일 |
| 미종결 | 0건 |
| 예약 | `UNKNOWN` 동안 `RESERVED` 유지 → 확정 후 `CONSUMED` |
| 가용 재고 | **추가 차감 없음** (예약 시점에 이미 차감됐다) |
| Outbox | 주문당 1건, `event_id` 중복 없음 |
| `confirmed_at` | 기존 확정 주문은 **그대로**, 새 확정 주문에만 생성 |
| 포인트 잔액·쿠폰 | PG 복구로 **변하지 않음** |
| 반복 reconcile·callback | 같은 업무 사실로 수렴 |

**create 증가 0을 어떻게 확인하는가.** 실험에서는 테스트 PG fixture의 수신 기록으로 셌다. 실제 PG에서는 **PG 측 요청 기록**이 필요하다. 그런 출처가 없으면 "추가 호출이 없었다"고 기록하지 않는다. 내부 로그의 `pg.create` 줄 수는 보조 증거이고 PG 수신 횟수를 대신하지 않는다.

`confirmed_at`을 PG 요청 시각이나 복구 실행 시각으로 덮어쓰지 않는다. 주간 집계의 기준이 이 값이다.

---

## 9. Kafka·구매 활동·랭킹의 업무 완료 기준

결제 복구는 끝의 일부다. 각 경계의 성공이 다음 경계를 보장하지 않는다.

| 경계 | 완료 기준 | 확인 |
| --- | --- | --- |
| Outbox | 대상 주문의 행이 `PUBLISHED` | `SELECT status FROM order_confirmed_outbox WHERE order_id IN (...)` |
| 소비 | 주문·상품당 활동 1행, 장부 1행 | 아래 SQL |
| 랭킹 | 새 snapshot이 공개되고 점수가 실제 입력과 맞음 | 랭킹 API |

```sql
-- 주문·상품·수량·확정시각이 줄 단위로 맞는지
SELECT a.order_id, a.product_id, a.quantity, a.confirmed_at
FROM product_purchase_activity a
JOIN orders o ON o.id = a.order_id
JOIN order_items i ON i.order_id = o.id AND i.product_id = a.product_id AND i.quantity = a.quantity
WHERE a.confirmed_at = o.confirmed_at AND a.order_id IN (<대상 주문 목록>);

-- Outbox 와 장부의 event_id 집합 일치
SELECT event_id FROM order_confirmed_outbox ORDER BY event_id;
SELECT event_id FROM processed_order_confirmation ORDER BY event_id;
```

Outbox가 `PENDING`이면 `next_attempt_at`과 `last_error`를, `BLOCKED`면 차단 원인을 확인한다. 자동 재시도로 해결됐다고 가정하지 않는다.

**발행 횟수와 업무 활동 수를 같은 수로 취급하지 않는다.** 재발행은 늘 수 있고, 활동은 `(order_id, product_id)` 유니크와 처리 장부로 1회만 남는다.

랭킹은 다음을 지킨다.

- **이미 공개한 snapshot을 직접 수정하지 않는다.** 늦게 도착한 사실은 **새 revision**으로 반영한다.
- 주간 소속은 내부 최초 `confirmed_at`이 결정한다.
- 새 집계가 필요하면 배치 실행 담당자가 같은 주의 새 revision을 만든다. 식별 파라미터는 `periodStart`(UTC 월요일)와 `snapshotId`(`weekly-YYYYMMDD-rN`) 두 개다.
- 공개 포인터는 `revision`이 더 큰 쪽으로만 이동한다. 과거 revision으로 되돌지 않는다.

```bash
curl --silent --show-error --connect-timeout 3 --max-time 30 --get \
  --data-urlencode 'periodStart=<해당 주 월요일 YYYY-MM-DD>' \
  '<api>/api/v1/rankings/weekly'
```

응답의 `snapshotId`가 새 실행의 것과 같은지 확인한다. 이전 공개 ID를 새 결과로 오인하지 않는다.

결제·주문은 끝났지만 후속이 안 맞으면 **주문 복구 완료·후속 미완료**로 구분하고 결제 복구를 반복하지 않는다.

---

## 10. 증거 보존

조치 **전에** 보존한다. 조치 후 기록은 별도로 남긴다.

1. 실행마다 새 디렉터리를 만든다. 과거 기록을 덮어쓰지 않는다.
2. 다음을 원문으로 저장한다.
   - 대상 주문 집합과 식별자 목록
   - 내부 상태 조회 결과(주문·시도·예약·Outbox)
   - PG 권위 조회 응답 본문
   - `health`와 `prometheus` 응답 본문
   - 경계 로그와 가설별 예상·실제·판정
3. 조치 전 기록의 해시 목록을 만들고, 목록 파일은 그 디렉터리 **밖**에 둔다.

```bash
shasum -a 256 '<조치 전 기록 디렉터리>'/* > '<해시 목록 파일>'
shasum -a 256 -c '<해시 목록 파일>'   # 조치 후 검증, 원문은 덮지 않는다
```

기록이나 해시 생성에 실패하면 복구를 중단한다. 해시는 파일이 바뀌지 않았음만 확인하고 내용이 참임을 증명하지 않는다.

**종료 상태를 대상별로 하나 고른다.**

| 종료 상태 | 뜻 |
| --- | --- |
| 원인 미확정·중단 | 증거가 부족해 결과 반영을 하지 않았다 |
| 복구 미완료 | reconcile을 호출했으나 확정되지 않았다 |
| 주문 복구 완료·후속 미완료 | 결제·주문은 확정됐으나 Outbox·활동·랭킹 중 일부가 안 맞는다 |
| 후속까지 확인 완료 | 상태와 효과가 모두 대사됐다 |

시각, 직전 응답, 확인하지 못한 항목, 남은 문제를 함께 남긴다. 후속 결과가 맞아도 관측·보존 실패를 숨기지 않는다.

---

## 11. 재진입

- 상태가 남아 있으면 같은 주문을 **4절부터** 다시 확인한다. 바로 재호출하지 않는다.
- 이미 확정됐으면 9절 후속만 확인한다.
- 상태가 사라졌으면 이전 주문을 복구했다고 기록하지 않는다.
- 실패한 실행의 기록도 보존한다.

---

## 12. 알려진 한계와 미검증

**로컬 합성 실험에서 확인한 것.** 응답 유실로 생긴 `UNKNOWN` 3건을 같은 거래 조회로 복구하고, create 증가 0·예약·재고·포인트·쿠폰 불변·Outbox 중복 없음·구매 활동 20행·새 revision의 랭킹 반영까지 한 실행에서 확인했다.

**이 절차가 증명하지 않는 것.**

- 운영 배포, 실계정, 실제 PG, 부하 상황
- 월간 SLO나 처리량
- 모든 종류의 장애. 이번은 생성 응답 유실 하나다
- 분산 추적. trace id는 한 요청 안의 로그를 잇지만 PG·Kafka로 문맥을 전파하지 않는다
- 운영자가 사용자를 대신해 복구하는 권한 경로
- `STATUS_UNKNOWN` 트랜잭션 완료 경로의 실행 검증
- 지표 집계 실패 후 스케줄러 자동 회복과 비활성 전환
- 쿠폰 발급과 PG 결제가 서로 영향을 주지 않는다는 명시적 회귀 테스트

**구조적 한계.**

- 결제 시도의 상태 진입 시각 컬럼이 없다. 나이는 `created_at` 기준이다.
- 지표 집계 쿼리에 timeout이 걸려 있지 않다. DB가 길게 응답하지 않으면 다른 스케줄 업무도 함께 밀린다.
- `management` 엔드포인트는 인증 없이 열려 있고 포트 분리에만 의존한다. 운영에서는 접근 제한이 필요하다.

**개선 항목은 확인할 수 있게 쓴다.** "모니터링 강화" 대신 "미확인 주문의 나이와 개수가 정한 한도를 넘으면 같은 거래 조회 절차로 연결한다"처럼 쓰고, 한도는 운영 기대와 측정 근거로 정한다.

---

## 부록. 실험 증거 참조

| 실행 | 범위 |
| --- | --- |
| `20261127-052251-d8d97825` | BASELINE → FAULT → DIAGNOSE → r1 공개 → RECOVERY → DOWNSTREAM 전 단계 |
| `20261127-051516-bb5ff22c` | r2 공개와 후속 결과 |
| `20261126-222348-37dab0a6` | 경쟁 가설 진단 |

증거는 `docs/week10/evidence/<runId>/`에 있다. 서술과 관측값은 `docs/week10/experiment-results.md`, 관측 설계는 `docs/week10/monitoring-design.md`에 있다.

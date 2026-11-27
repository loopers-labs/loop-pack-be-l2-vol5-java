# Runbook · 결제 확인 중으로 남은 주문 (PG 성공 응답 유실 의심)

> 대상: commerce-api의 PG 카드 결제 (`POST /api/v1/orders/{id}/confirm`).
> 근거 실험: `apps/commerce-api/src/e2eTest/.../CheckoutIncidentE2ETest` (로컬 통제 사고), 대표 증거 `docs/week10/evidence/`.
> 이 문서는 로컬 테스트 PG fixture로 재현·복구한 절차를 정리한 것이다. 실제 PG·운영 DB·부하 조건에서 검증한 절차가 아니다.

## 0. 이 runbook이 다루는 상태

| 저장소 상태 | 의미 |
|---|---|
| `payment_attempt.status = UNKNOWN` | PG 생성 요청을 보냈지만 결과를 확인하지 못했다. 거래가 생겼을 수 있다 |
| `orders.status = PAYMENT_PENDING` | 고객 화면의 "결제 확인 중" |
| `stock_reservation.status = RESERVED` | 재고는 확보된 채 유지 중 (반환하지 않음) |
| `payment_attempt.unknown_reason` | `TIMEOUT` · `CONNECTION_LOST` · `UNEXPECTED_STATUS` · `INVALID_BODY` · `ECHO_MISMATCH` |

`PENDING`(PG가 처리 중이라고 응답함)은 정상 처리 중이므로 이 runbook의 대상이 아니다.

## 1. 증상

- 고객: 주문이 계속 "결제 확인 중"이다. 카드 승인 알림은 받았다고 말할 수 있다.
- 지표: `commerce_payment_unresolved{status="UNKNOWN"}` 가 0보다 크다. 값은 DB 기준이며 `payment.metrics.unresolved.cache-ttl`(기본 5s)만큼 늦을 수 있다.
- **health는 UP일 수 있다.** health는 프로세스·설정한 상태 검사만 본다. 특정 주문의 결제가 끝났는지는 알려 주지 않는다. 사고 실험에서도 세 단계 모두 `{"status":"UP"}`였다.
- 로그: `pg.create.result ... outcome=UNKNOWN reason=TIMEOUT` (warn 문구 `PG 거래 생성 결과를 확인하지 못했다`).

## 2. 먼저 할 일 (조치 전에)

1. **새 시도로 영향을 늘리지 않는다.** 같은 증상이 계속 늘면(지표 증가) PG 연동 장애일 수 있다. 원인을 가리기 전에는 새 결제 시도 유입을 제한할지 결정한다(완화). 이미 UNKNOWN인 주문은 이 조치로 해결되지 않는다.
2. **대상 주문 목록을 고정한다.** 아래 질의 결과를 저장하고, 이후 조치는 이 목록만 대상으로 한다.
3. **증거를 보존한다.** 대상 주문의 DB 상태, health·Prometheus 응답, 대상 주문 번호의 경계 로그를 조치 전에 저장한다. 조치 뒤에 다시 만들 수 없다.

### 2-1. 대상 주문 (읽기 전용)

```sql
select a.order_id, a.user_id, a.attempt_key, a.provider_order_id, a.transaction_key, a.amount,
       a.status as attempt_status, a.unknown_reason, a.conflict_reason, a.updated_at,
       o.status as order_status, r.status as reservation_status
from payment_attempt a
join orders o on o.id = a.order_id
join stock_reservation r on r.order_id = a.order_id
where a.status = 'UNKNOWN'
order by a.updated_at;
```

### 2-2. 주문별 후속 사실 (읽기 전용)

```sql
-- 확정되지 않았다면 Outbox 행이 없어야 한다
select order_id, event_id, status, attempt_count from order_outbox where order_id in (:ids);
-- 구매 활동도 없어야 한다 (commerce-streamer 테이블)
select order_id, product_id, quantity from product_purchase_activity where order_id in (:ids);
```

### 2-3. 경계 로그 (주문 번호 `LP-ORD-{orderId 10자리}`로 찾는다)

| event | 보는 값 |
|---|---|
| `payment.attempt.created` | orderId, attemptKey, providerOrderId, amount |
| `pg.create.result` | outcome(ACCEPTED/REJECTED/UNKNOWN), reason, transactionKey |
| `payment.apply` | from → to, effect(SUCCEEDED/FAILED/NONE), unknownReason, conflict, eventId |
| `payment.apply.tx` | 그 적용 트랜잭션이 COMMITTED인지 ROLLED_BACK인지 |
| `pg.lookup.result` | 조회한 거래 수, 거래별 키·주문·금액·상태 |

## 3. 진단: 경쟁 가설과 구분 근거

조치 전에 가설과 예상 관찰을 적는다. **PG SUCCESS + 내부 UNKNOWN만으로는 H2와 H3를 가를 수 없다.** 경계 로그와 트랜잭션 결과까지 본다.

| 가설 | 예상 관찰 | 구분 근거 |
|---|---|---|
| H1 PG 처리 자체가 지연 | 권위 조회도 PENDING | 같은 주문 번호의 거래 조회 결과 |
| H2 PG 성공 응답 유실 | PG SUCCESS, `unknown_reason=TIMEOUT`(또는 CONNECTION_LOST), `pg.create.result outcome=UNKNOWN`, `payment.apply REQUESTED→UNKNOWN` COMMITTED, 성공 적용 시도 없음 | adapter 결과 로그 + 시도 상태 + 거래 조회 |
| H3 성공 결과의 내부 반영 실패 | PG SUCCESS, `payment.apply effect=SUCCEEDED` 뒤 `payment.apply.tx tx=ROLLED_BACK` | 적용·TX 로그, 예약·Outbox 상태 |

- **진단용 거래 조회는 읽기만 한다.** reconcile API는 결과를 적용하므로 진단에 쓰지 않는다. 증거를 보존한 뒤에만 부른다.
- 증거가 부족하면 "미확정"으로 적는다. 숨은 원인을 맞히는 것이 목적이 아니다.
- 분산 trace는 구현돼 있지 않다. `traceId`만으로 같은 업무를 판단하지 않는다(나중의 reconcile은 다른 trace다). 주문 번호로 잇는다.
- AI에게 경계 로그와 가설 표를 주고 비교를 맡길 수 있다. 판정과 상태 변경 조치는 사람이 범위·중단 조건을 확인한 뒤 한다.

## 4. 조치 전제

- 대상 주문 목록이 고정돼 있다 (2-1 결과 저장).
- 조치 전 증거가 저장돼 있다 (2절).
- H1이 아니다 (권위 조회가 PENDING이면 기다리고, 4절 조치를 하지 않는다).
- 조치는 **기존 거래 조회로 결과를 적용하는 경로** 하나뿐이다. 새 결제 요청(create)이나 DB 직접 수정은 조치가 아니다.

## 5. 조치: 같은 주문의 기존 거래 조회로 마무리

```
POST /api/v1/orders/{orderId}/payment/reconcile
X-USER-ID: {주문 소유자 user_id}
```

- 이 API는 PG에 **조회만** 한다(`transaction_key`가 있으면 단건, 없으면 주문 번호로). create를 보내지 않는다.
- 같은 계정·주문 번호·금액·거래 키가 일치하는 **SUCCESS 1건**일 때만 주문 확정·예약 소비·Outbox 기록을 한 트랜잭션으로 적용한다.
- 다른 사용자의 주문은 404다. 운영자가 대신 부를 관리자 경로는 없다.
- 응답 200은 결제가 끝났다(확정 또는 실패), 202는 아직 모르거나 조회하지 못했다는 뜻이다.

## 6. 중단 조건 (아래면 멈추고 상태를 보존한다)

| 관찰 | 시스템 동작 | 운영자 행동 |
|---|---|---|
| 조회 결과 0건 | 상태 그대로 (0건은 거래가 영원히 없다는 증거가 아니다) | 멈춘다. 재결제하지 않는다. PG 측 확인을 요청한다 |
| 같은 주문 번호의 거래 2건 이상 | 상태 그대로, `conflict_reason=MULTIPLE_TRANSACTIONS` | 멈춘다. 첫 거래를 고르지 않는다. 중복 결제 여부를 PG와 확인한다 |
| 금액·주문·거래 키 불일치 | 상태 그대로, 충돌 기록 | 멈춘다. 다른 거래의 응답일 수 있다 |
| 계속 PENDING | 상태 그대로 | 기다린다. 정한 횟수 뒤에도 같으면 멈추고 PG에 문의한다 |
| 조회 실패·회로 열림 (202, `lookupUnavailable`) | 상태 그대로 | 조회는 재시도·회로 차단기로 제한돼 있다. 한 주문에 반복 호출하지 않고 PG 상태를 먼저 본다 |

**하지 않는 것**

- UNKNOWN 주문에 `confirm`을 다시 보내거나 새 결제를 만들지 않는다. 응답 timeout은 거래가 없다는 뜻이 아니다. 중복 결제가 생긴다.
- `orders`·`payment_attempt`·`stock_reservation`을 직접 UPDATE해 CONFIRMED·SUCCESS로 만들지 않는다.
- UNKNOWN 동안 예약을 반환하지 않는다.
- 프로세스 재시작이나 코드 rollback으로 해결됐다고 보지 않는다. 외부 거래와 DB 사실은 그대로 남는다.

## 7. 복구 확인 (같은 주문 목록, 새 조회로)

- [ ] 대상 주문: `orders.status=CONFIRMED`, `payment_attempt.status=SUCCESS`, `stock_reservation.status=CONSUMED`
- [ ] `commerce_payment_unresolved` 가 대상 수만큼 줄었다
- [ ] 복구 중 PG create 호출이 늘지 않았다 (PG 측 요청 기록 또는 거래 수로 확인)
- [ ] 재고가 추가로 줄지 않았다 (예약을 소비만 했다)
- [ ] 주문마다 `order_outbox` 1행, relay 발행 뒤 구매 활동이 품목·수량대로 1회
- [ ] 포인트 잔액·쿠폰은 변하지 않았다 (PG 결제와 무관)
- [ ] `confirmed_at`은 처음 확정된 시각(복구 시각)이다. PG 요청 시각으로 바꾸지 않는다. 주 경계를 넘었으면 그 저장 시각의 주로 집계된다
- [ ] 이미 공개된 주간 랭킹 snapshot은 바뀌지 않는다. 반영이 필요하면 새 snapshotId(revision)로 job을 실행한다

사고 실험의 결과(장애 주문 10건):

| 단계 | 내부 성공 | UNKNOWN | PG SUCCESS | 누적 거래·create | unresolved | health |
|---|---|---|---|---|---|---|
| FAULT | 7 | 3 | 10 | 20 · 20 | 3 | UP |
| RECOVERY | 10 | 0 | 10 | 20 · 20 | 0 | UP |

## 8. 재진입 (중간에 멈췄거나 일부만 복구됐을 때)

1. 2-1 질의를 다시 실행한다. 이미 확정된 주문은 목록에서 빠진다.
2. reconcile은 같은 주문에 다시 불러도 안전하다. 종결된 시도는 어떤 관찰로도 바뀌지 않고, 확정·예약 소비·Outbox가 늘지 않는다 (실험에서 재호출·늦은 callback 뒤 상태 불변 확인).
3. 6절 중단 조건에 걸린 주문은 상태와 `conflict_reason`을 그대로 두고 별도 목록으로 넘긴다. 다른 주문의 복구를 막지 않는다.
4. 실패한 시도의 기록(응답, 로그)도 지우지 않고 남긴다.

## 9. 후속 개선 (확인할 수 있는 형태로)

- `commerce_payment_unresolved`가 0보다 큰 상태가 N분 이상 지속되면, 대상 주문 목록을 뽑아 5절 조회 절차로 연결한다. N과 임계값은 실제 PG의 결과 확정 지연 분포를 측정한 뒤 정한다(현재 근거 없음).
- 자동 reconcile scheduler는 아직 없다. 도입하면 같은 중단 조건과 호출 한도를 지켜야 한다.
- UNKNOWN의 나이(가장 오래된 `updated_at`)를 지표로 추가하면 "몇 건"뿐 아니라 "얼마나 오래"를 볼 수 있다.
- `payment_attempt.status`에 인덱스가 없어 지표가 전체 행을 센다. 행이 많아지면 인덱스나 집계 방식을 다시 정한다.

## 10. 검증 범위

| 확인한 것 | 확인하지 않은 것 |
|---|---|
| 로컬 테스트 PG fixture로 만든 응답 유실 3건의 진단과 같은 주문 복구 | 실제 PG, 실제 카드, 운영 DB |
| 실제 MySQL·Redis·Kafka(Testcontainers), 실제 relay·consumer·weeklyRanking job | 부하·동시 사고·다중 인스턴스 |
| 실제 관리 포트의 health·Prometheus 응답 | 운영 관리 포트 노출·접근 제어 구성 |
| create 0건 추가, 예약·재고·포인트·쿠폰 불변, 반복 적용 없음 | 분산 trace (구현하지 않음) |
| 10건 통제 실험 | SLO 달성 (10건으로 월간 목표를 주장하지 않는다) |

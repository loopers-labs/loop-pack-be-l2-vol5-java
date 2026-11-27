# 결제 장애 대응 런북 · 미확인 주문을 발견했을 때

**먼저 대상 주문번호를 기록한다.** 서버 상태가 `UP`이어도 결제 결과를 기다리는 주문이 남을 수 있다. Grafana의 건수로 영향 규모를 보고, 같은 실행의 DB·로그와 PG(결제 대행사) 거래를 주문별로 대조한다. [935번과 2번의 실제 판단](../week10/README.md)

## 1. 대상 주문과 증거 찾기

Grafana에서 **미확인 주문**, **집계 신선도**, **Prometheus 수집 상태**를 함께 본다. ‘확인 불가’이면 `up`과 마지막 성공 집계 시각부터 확인한다. DB 조회에 실패하거나 발송한 `UNKNOWN` 주문의 응답 기한이 누락되면 전체 건수를 보류한다. [지표 판독 기준](../week10/adr-0002-payment-metric-refresh.md)

1. **주문번호를 찾는다.** 관리자 읽기 API `GET /api-admin/v1/payments/incomplete?page=0&size=20`에서 후보 주문을 찾는다. 필요하면 다음 페이지를 확인한다. 이 목록에는 결제 대기, 수동 조치 대상, 충돌 주문이 포함된다. 따라서 목록의 전체 건수는 Grafana의 ‘기한 초과 UNKNOWN’ 건수와 다를 수 있다. 이미 주문번호를 알면 상세 조회로 바로 간다. [관리자 미완료 목록](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/ordering/controller/AdminPaymentQueryController.java) · [목록의 선정 조건](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/query/ordering/QueryDslOrderQueryDao.java)
2. **우리 서버가 아는 결과를 확인한다.** 관리자 권한으로 `GET /api-admin/v1/orders/935`를 읽는다. 주문 상태, 결제 시도의 `dispatchState`·`outcome`·기존 `transactionKey`를 확인한다. 같은 실행 로그에서 `orderId=935 `와 시도 키(`attemptKey`)를 맞춰 `PG_CREATE`, `DB_OBSERVATION`, PG에 전달한 `providerOrderId`를 찾는다. [관리자 주문 상세](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/ordering/controller/AdminOrderQueryController.java)
3. **PG가 아는 결과를 확인한다.** 해당 가맹점 계정에서 로그의 PG 주문번호로 전체 거래 목록을 읽는다. 935번의 예는 `GET /api/v1/payments?orderId=LP-ORD-0000000935`다. 이 실습의 PG 조회 계정은 `X-USER-ID: 135135`, Commerce에서 주문을 소유한 사용자는 `1002`다. [실험에서 사용한 PG 조회](../week10/control/incident.py)

**SQL이 필요한 경우.** 현재 구현은 주문 총액을 PG 요청 금액으로 저장하므로 일차 대조에는 상세 API의 `totalAmount`를 쓸 수 있다. 다만 상세 API에는 저장된 응답 기한이 없다. 특정 주문이 정확히 언제 기한을 넘겼는지, 또는 최초 결제 시도에 어떤 금액이 저장됐는지 원본 확인이 필요할 때 DB 읽기 권한을 가진 담당자가 **그 주문 한 건만** 아래처럼 조회한다. DB 자체가 느리거나 응답하지 않으면 반복 조회를 멈추고 ‘확인 불가’로 남긴다. [결제 시도의 금액 저장](../../apps/commerce-api/src/main/java/com/loopers/application/ordering/service/PrepareOrderPaymentService.java)

```sql
SELECT order_id, attempt_key, provider_order_id, amount AS expected_amount,
       dispatch_state, outcome, response_deadline,
       response_deadline < UTC_TIMESTAMP(6) AS overdue, transaction_key, conflict_reason
FROM payment_attempts
WHERE order_id = 935;
```

`STARTED`, `UNKNOWN`, `overdue = 1`이 함께 맞으면 해당 관측 시점의 미확인 주문이다. `overdue`가 `NULL`이면 기한을 판정할 수 없다. 복구 전에 실행명·관측 시각과 주문 조회 결과, 두 로그, PG 거래 목록 또는 오류를 저장한다. 실습에서는 DB에 실제로 저장된 값도 복구 전후에 별도 파일로 남겼다. [935번 복구 전 증거](../week10/evidence/2026-11-27-catalog/incident/incident-comparison.json)

## 2. 주문별 결정표

| PG와 DB를 대조한 결과 | 지금 할 일 | 재진입 조건 |
|---|---|---|
| 같은 가맹점에서 **총 1건**. 주문번호·예상 금액이 일치하고 최종 `SUCCESS`. 거래 키가 있고 DB의 기존 키와도 일치(기존 키가 있는 경우) | 충돌 기록이 없고 여전히 결제 대기면 아래 복구 API를 호출한다. 935번은 1,000원이 일치했다. | 이미 같은 거래로 확정됐다면 복구 후 확인 단계로 간다. |
| 조회 성공, **0건** | 현재 `UNKNOWN`을 유지하고 빈 결과와 조회 시각을 남긴다. | 같은 주문번호의 거래가 제공되면 다시 대조한다. |
| 조회 실패·시간 초과 | 현재 상태를 보존하고 조회 오류를 남긴다. | PG 조회가 정상화되면 같은 계정과 주문번호로 다시 조회한다. |
| 총 1건, 같은 거래가 `PENDING` | PG 처리 중이다. 주문을 결제 대기로 두고 거래 키를 기록한다. | 같은 거래가 최종 상태가 되면 다시 읽는다. |
| **복수 거래** | 주문 복구를 중단하고 조회된 모든 거래 키를 기록한다. | 담당자가 중복 원인을 조사하고 주문과 거래의 대응을 확인하면 다시 대조한다. |
| 금액·주문번호·기존 키 불일치, 계정 불명 또는 DB 충돌 기록 | 해당 주문의 복구를 중단하고 어느 필드가 다른지 남긴다. | 담당자가 불일치를 해소한 뒤 DB와 PG를 새로 읽는다. |
| 총 1건, 동일한 거래가 최종 `FAILED` | 승인 복구를 멈추고 기존 실패 처리 경로로 주문 결과를 확인한다. | 주문이 계속 결제 대기면 실패 결과의 내부 반영을 조사한다. |

확인할 수 없는 주문은 그 회차에서 멈추고 주문번호와 사유를 보류 목록에 남긴다. 다른 주문은 각각 판단한다. 새 PG 결과나 콜백이 도착하면 보류한 주문부터 다시 조회한다. 결과가 불확실한 동안 예약을 유지하며 재결제나 DB 직접 수정은 하지 않는다.

## 3. 복구와 종료 확인

1. **기존 승인 반영.** 호출 직전 주문 소유자·결제 시도·PG 거래가 보관한 증거와 일치하는지 다시 읽는다. 담당자는 관리자 복구 API `POST /api-admin/v1/orders/{orderId}/payment/reconcile`을 사용한다. 935번 실습에서는 소유자용 `POST /api/v1/orders/935/payment/reconcile`을 사용했다. 응답이 끊기거나 HTTP 202면 주문과 PG 현황을 다시 조회한 뒤 위 표에서 판단한다. [복구 API](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/ordering/controller/ReconcileOrderPaymentController.java)
2. **고객 주문 확인.** 주문 상세를 새로 읽어 `CONFIRMED`, 결제 시도 `SUCCESS`, 결제 기록 `PAID`와 금액을 확인한다. PG에도 원래 거래 하나만 있어야 하며 금액·거래 키가 주문 상세와 같아야 한다. 최초 영향 주문 목록 전체를 확인하고 다음 성공 집계와 대조한다. 실습에서는 DB에 남은 결제 기록도 함께 대조했다. [복구 후 DB·PG 대조 예시](../week10/evidence/2026-11-27-catalog/incident/incident-recovery.json)
3. **후속 반영 확인.** 주문별 예약 소비·재고 차감과 확정 이벤트가 한 번인지 점검한다. 해당 이벤트가 구매 활동에 한 번 반영됐는지 주문·상품·수량으로 맞추고, 확정 시각이 속한 주의 공개 랭킹까지 확인한다. 남은 항목은 주문번호와 함께 추적한다. [20건 시나리오의 검증 범위](../week10/README.md)

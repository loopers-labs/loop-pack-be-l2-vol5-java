# Week 10 결제 관측·재조정 API 계약

이 문서는 [commerce API 공통 계약](../week2/api-contract.md)과 기존 OpenAPI의 C-13 계약을 구체화한다. 기존 경로·응답 코드는 바꾸지 않는다.

## 본인 주문 결제 결과 재조정

```http
POST /api/v1/orders/{orderId}/payment/reconcile
X-USER-ID: 42
```

요청 본문은 없다. `orderId`와 고객 식별은 기존 commerce API 규칙을 따른다. 다른 사용자의 주문은 주문 존재 여부와 구분하지 않고 `404 ORDER_NOT_FOUND`로 처리한다.

| 조건 | 동작·응답 |
| --- | --- |
| 결제 시도가 없음 또는 PG 결제가 아님 | `400 INVALID_REQUEST`; PG 요청·내부 상태 변경 없음 |
| Payment가 `SUCCESS` 또는 `FAILED` | 저장된 동일 최종 결과를 `200`으로 반환; PG create·조회 및 추가 적용 없음 |
| Payment가 `PENDING` 또는 `UNKNOWN` | 저장된 payment key로 권위 거래 조회를 수행한다. PG create는 호출하지 않는다. |
| 권위 조회가 일시 실패·미확정·거래 없음(404) | 상태·예약을 보존하고 `202 PENDING` 반환 |
| 조회된 성공 거래의 payment key·provider order ID·금액 중 하나가 저장된 결제와 불일치 | 내부 성공·실패로 바꾸지 않고 상태·예약을 보존한 뒤 `202 PENDING` 반환 및 상관 로그 기록 |
| 동일 주문·금액의 권위 성공 거래 | 기존 결과 적용 트랜잭션으로 주문 확정·예약 소비·Outbox 기록 후 `200 SUCCESS` 반환 |
| 권위 PG가 결제 실패를 확정 | 기존 결과 적용 트랜잭션으로 `FAILED` 기록·예약 반환 후 `200 SUCCESS` 반환 |

조회 결과가 끝까지 미확정이면 유한한 조회를 마친 뒤 `202`로 종료한다. 이 endpoint는 재결제를 시작하거나 client가 준 거래 정보를 신뢰하지 않는다. `PENDING`/`UNKNOWN`을 성공으로 바꾸는 별도 강제 조정 기능은 제공하지 않는다.

### 성공 응답

```json
{
  "meta": { "result": "SUCCESS", "errorCode": null, "message": null },
  "data": { "orderStatus": "CONFIRMED", "paymentStatus": "SUCCESS" }
}
```

확정 실패를 반영한 경우 `data.orderStatus=DRAFT`, `data.paymentStatus=FAILED`다. 두 경우 모두 결과 반영 완료이므로 HTTP 200이다.

### 미확정 응답

```json
{
  "meta": { "result": "PENDING", "errorCode": null, "message": null },
  "data": {
    "orderStatus": "DRAFT",
    "paymentStatus": "UNKNOWN",
    "statusUrl": "/api/v1/orders/42"
  }
}
```

실제 저장 상태가 `PENDING`이면 응답에도 `PENDING`을 반환한다. 이 JSON의 숫자는 Prometheus sample이 아니며 metric 원문은 `/actuator/prometheus`에서 읽는다.

### 실패와 보호 경계

| 상황 | HTTP | 결과 |
| --- | ---: | --- |
| 고객 식별 누락·오류 | 401 | 기존 `USER_NOT_IDENTIFIED` |
| 주문 없음 또는 다른 사용자의 주문 | 404 | `ORDER_NOT_FOUND` |
| 입력·결제 시도가 재조정 가능한 상태가 아님 | 400 | `INVALID_REQUEST` |
| PG timeout·조회 결과 미확정·거래 식별/금액 불일치 | 202 | 상태 보존, 기존 주문 상태 URL 반환 |

상관 로그는 합성 환경에서 `orderId`, `paymentAttemptKey`, 이전/이후 내부 상태, PG 결과 분류, 적용 결과를 기록한다. secret key, 인증 헤더, 사용자 개인정보, 원문 카드 정보를 기록하지 않는다.

## 업무 지표와 관리 endpoint

| 항목 | 계약 |
| --- | --- |
| metric | `commerce.payment.unresolved` gauge; label 없음 |
| 값 | 최근 DB 조회에서 `PENDING` 또는 `UNKNOWN`인 주문 수 합계 |
| 갱신 | 5초 fixed delay의 DB count 조회; 마지막 성공 refresh 시점 기준 최대 5초 + query duration의 관찰 지연 |
| 실패 시 | 기존 마지막 값을 유지하고 오류 상관 로그를 남긴다. 실패를 0으로 기록하지 않는다. |
| health | 실제 `/actuator/health` 응답을 확인한다. health UP은 주문 확정 완료를 의미하지 않는다. |
| Prometheus | 실제 `/actuator/prometheus` text exposition에서 gauge를 확인한다. |
| 노출 | monitoring 설정의 health·prometheus 노출만 사용하고 env·heapdump endpoint를 추가하지 않는다. 기존 관리 접근 보호를 유지한다. |
| 테스트 포트 | `management.server.port=0`을 사용하고 Spring이 주입한 실제 management port로 요청한다. |

# 미완료 결제를 확인하고 처리하는 방법

[실제 결과](result.md) · [재현 방법](verification.md) · [결제 상태](../states/payment.md) · [회로 집계 기준](trade_off/02-lookup-circuit-breaker.md)

처음 읽는다면 [장애 대응 읽기 안내](../resilience/README.md)에서 상태 모델·서킷브레이커·폴백의 역할을 먼저 확인한다. 이 문서는 담당자가 실제로 조회하고 처리하는 순서를 설명한다.

## 먼저 저장된 상태를 읽는다

결제 요청이 202로 끝나면 아직 성공·실패 업무가 완료되지 않은 것이다. 새 결제를 보내지 말고 응답의 조회 URL을 따라 현재 상태를 읽는다. 고객 URL은 본인 주문만, 관리자 URL은 학습용 1번 fixture로 확인한다.

```http
GET /api-admin/v1/payments/incomplete?page=0&size=100
X-USER-ID: 1

GET /api-admin/v1/orders/{orderId}
X-USER-ID: 1
```

고객 상세는 `GET /api/v1/orders/{orderId}`다. 고객은 자신의 `X-USER-ID`를 사용한다. 타인 주문은 없는 주문과 같은 404다. 이 헤더는 학습용 fixture이며 실제 로그인 체계는 아니다.

GET은 우리 DB를 읽기만 한다. PG 조회·재결제·자동 작업 재시작을 하지 않는다. 상세 응답은 주문 `status`와 `paymentAttempt`의 결과·복구 상태·회차·최근 확인 시각·사유·주의 표시를 함께 본다. 고객 confirm·고객/관리자 reconcile·주문 GET 본문에는 `completed`가 없다. confirm/reconcile은 내부 완료 판단을 HTTP200/202로 표현하며, 본문은 status와 recoveryState로 읽는다. `completed`가 노출되는 경로는 별도 callback 응답이다.

| 저장 상태 | 의미와 처리 |
|---|---|
| CONFIRMED / APPLIED | 성공 업무 반영 완료. 재고는 차감 상태를 유지한다 |
| PAYMENT_FAILED / APPLIED | 확정 실패 반영 완료. 재고는 한 번 반환됐다 |
| PAYMENT_PENDING + 미확인/PENDING | 결제 결과가 아직 확정되지 않았다. 차감 재고를 유지한다 |
| SUCCESS 또는 FAILED + 미반영 | PG 사실은 확인했으나 우리 업무가 끝나지 않았다. 저장 근거로 재적용할 수 있다 |
| MANUAL_REQUIRED | 자동 처리를 멈췄다. 이 상태만으로 결제 실패를 뜻하지 않는다 |
| RUNNING + 오래된 최근 시각 | 큐 대기 또는 종료된 실행일 수 있다. 자동 회수는 없으므로 담당자가 확인한다 |

완료 상태를 사유보다 먼저 읽는다. 완료 후에도 이전 `PENDING`·`CIRCUIT_BLOCKED`·`APPLICATION_FAILED` 사유가 남을 수 있다. 늦게 발견된 결과 충돌은 이미 반영한 업무를 되돌리는 대신 기존 완료 상태와 주의로 표시한다.

## 중단 사유를 구분한다

| 사유 | 다음 행동 |
|---|---|
| RETRY_EXHAUSTED | 최초 포함 4회 예산을 사용했다. PG 완료 시점을 고려해 명시적 대사한다 |
| TIME_BUDGET_EXHAUSTED | 30초 예산 뒤 새 자동 조회를 멈췄다. 자동 회차가 4 미만일 수도 있다 |
| EXECUTOR_REJECTED | 작은 실행기가 포화됐다. 100ms 뒤 추가 접수도 실패해 수동으로 넘겼다 |
| CIRCUIT_BLOCKED | PG에 조회하지 않았다. 회로 회복 뒤 명시적 대사로 확인한다 |
| LOOKUP_UNAVAILABLE | timeout·통신·재시도 가능한 HTTP 장애 등이다. 결제 실패로 추측하지 않는다 |
| LOOKUP_REQUEST_ERROR | 권한·요청 문제다. 요청과 PG 설정을 확인한 뒤 대사한다 |
| LOOKUP_CONTRACT_ERROR | 응답 형식·상태 코드가 계약과 다르다. 계약 문제를 먼저 확인한다 |
| APPLICATION_FAILED | PG 사실과 업무 반영 상태를 함께 본다. DB/재고 한도 등 원인을 해결한 뒤 저장 사실을 재적용한다 |

세부 분류는 [정책표](trade_off/01-reconciliation-retry-policy.md)를 따른다. 정상 형식의 FAILED 응답은 조회 장애가 아니다. 검증된 확정 실패만 주문 실패·재고 반환 근거가 된다.

## 실제 처리는 명시적인 POST로 한다

```http
POST /api-admin/v1/orders/{orderId}/payment/reconcile
X-USER-ID: 1
```

고객 경로는 `POST /api/v1/orders/{orderId}/payment/reconcile`다. 본인 주문만 처리할 수 있다.

수동 대사는 실행 ID를 교체해 중단 실행의 늦은 진행 갱신을 차단한다. 신뢰할 종결 사실이 이미 있으면 PG 호출 없이 공통 업무 TX를 다시 적용한다. 그 외에는 요청당 PG 조회 최대 1회다. 완료면 200, 여전히 미완료면 202로 반환한다. 자동 조회 회차를 소비/초기화하거나 새 자동 작업을 시작하지 않는다.

commerce 재시작 뒤 남은 RUNNING도 같은 절차로 인계한다. 같은 confirm을 반복해도 자동 예산을 새로 받지 않는다. PG 생성 POST를 재발송하거나 timeout만으로 재고를 돌려주지 않는다.

## 회로와 지표를 확인한다

payment-lab 관리 포트는 8082다. 기존 Prometheus endpoint는 `GET http://127.0.0.1:8082/actuator/prometheus`, health는 `/actuator/health`다. 회로가 OPEN이어도 이 실험의 health는 200이었다. health만으로 PG 조회 가능 여부를 판단하지 않는다.

```promql
resilience4j_circuitbreaker_state{name="pg-lookup",state="open"}
resilience4j_circuitbreaker_state{name="pg-lookup",state="half_open"}
resilience4j_circuitbreaker_calls_seconds_count{name="pg-lookup"}
resilience4j_retry_calls_total{name="payment-auto-lookup"}
resilience4j_retry_calls_total{name="payment-application"}
```

회로는 최근 10건·최소 5건부터 실패율 50% 또는 1초 초과 느림 비율 50%로 판단한다. OPEN 10초 뒤 **새 조회 요청**이 탐색을 시작한다. 빠른 정상 표본 두 건이면 CLOSED로 돌아오고 HALF_OPEN에서 최대 10초 내 판단하지 못하면 다시 차단한다. 한 수동 요청이 탐색을 두 번 보내지는 않는다.

위 호출 지표는 라이브러리 실행 결과다. 결제 성공률·주문 수·PG 실제 HTTP 수와 같지 않다. 회로 차단은 HTTP 0회여도 자동 회차를 소비할 수 있고, 연결 실패도 회차는 남는다. 주문별 과정은 주문/시도/실행 ID·회차·사유를 포함한 로그로 연결한다. 지표 태그에 이런 ID를 넣지 않는다. 카드·응답 원문도 기록하지 않는다.

회로 표본·Retry 지표는 재시작하면 초기화되지만 DB의 사용 회차와 결과는 남는다. OPEN 종료가 중단된 자동 작업을 되살리지도 않는다. Retry-After 헤더는 지원하지 않는다. 별도 대시보드·운영 API·자동 lease 회수는 이번 범위에 추가하지 않았다.

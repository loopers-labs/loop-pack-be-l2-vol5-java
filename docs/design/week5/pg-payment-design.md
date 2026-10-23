# 주문 결제의 PG 교체 설계 (W5)

> **PG를 기다리는 시간과 PG에서 실제로 생긴 결과를 구분한다. 결과는 PG에 다시 물어 확인한 뒤 주문·재고에 한 번만 반영한다.**

주문 확정의 결제를 포인트에서 로컬 테스트 PG(HTTP)로 교체한다. 구현 상세(상태 전이, 오류 우선순위, 시나리오)는 [AI용 상세](pg-payment-design.ai.md)에 있다.

## 핵심 결정

| 결정 | 이유 |
|---|---|
| 확정을 세 단계로 나눈다: 준비(TX A) → PG 요청 → 결과 반영(TX B) | PG 호출 동안 DB 연결과 재고 잠금을 쥐지 않기 위해서 |
| PG 요청 전에 재고를 차감하고, 실패가 확정될 때만 돌려준다 | PG 성공 뒤 재고 부족을 만나지 않기 위해서. 중복 반환은 주문 상태 전이가 막는다 |
| 결제 시도(`PaymentAttempt`)를 별도로 저장하고, 주문 잠금과 유일 제약으로 PG 요청 권한을 하나만 준다 | 재시작·동시 요청에도 PG 요청이 한 번만 나가게 하려고 |
| 결제 요청은 재시도하지 않고, 결과를 모르면 `UNKNOWN`으로 둔다. 복구는 조회로만 한다 | 테스트 PG는 멱등 키를 받지 않아 다시 보내면 이중 결제가 생길 수 있어서 |
| 결과 반영은 한 곳(`PaymentStatusUpdater`)으로 모으고, PG 알림(callback)은 확인 요청의 신호로만 쓴다 | 위조·중복·순서 뒤바뀜이 있어도 같은 결과로 수렴시키려고 |
| 재시도와 회로 차단은 조회에만 건다 | 조회는 다시 실행해도 안전하지만 결제 요청은 거래를 또 만들 수 있어서 |

## 흐름

```
POST /orders/{id}/confirm
 ├─ TX A  주문 잠금 → 재고 차감 → 시도(REQUESTED) 저장 → 주문 PAYMENT_PENDING   (commit)
 ├─ PG    결제 요청 (재시도 없음)                                              (TX 밖)
 └─ TX B  PG 응답을 시도·주문에 반영                                            (응답 유실이면 UNKNOWN)

POST /orders/{id}/payment/reconcile ┐  PG에 결제 상태를 묻고
POST /payments/callback             ┘  TX B와 같은 곳(PaymentStatusUpdater)에서 반영
```

## 상태

```
주문   DRAFT → PAYMENT_PENDING → CONFIRMED | PAYMENT_FAILED
시도   REQUESTED → PENDING | UNKNOWN → SUCCEEDED | FAILED
```

- 결제 성공 확인 시 주문 `CONFIRMED`, 재고 유지. 실패 확인 시 `PAYMENT_FAILED`, 재고 한 번 반환.
- 응답을 모르면 실패로 단정하지 않는다. 확인될 때까지 재고를 확보한 채 둔다.

## API

| 요청 | 결과 |
|---|---|
| `POST /api/v1/orders/{id}/confirm` | PG 결과로 끝났으면 200, 처리 중이거나 결과를 모르면 202 |
| `POST /api/v1/orders/{id}/payment/reconcile` | 같은 응답 모양. PG를 못 물었으면 저장된 상태와 `lookupUnavailable` 표시 |
| `POST /api/v1/payments/callback` | PG 수신 확인. 본문의 상태는 쓰지 않고 PG에 다시 물어 확인한 사실만 반영 |

## 알려진 한계

- 오래된 `UNKNOWN`·`REQUESTED`는 자동으로 종결하지 않는다. 확인 전까지 재고가 점유된다.
- 요청을 기록만 하고 실제로 보내지 못한 시도는 영구 미완료일 수 있다(중복 방지가 우선이다).
- 같은 주문에 같은 키로 카드 값만 바꿔 다시 보내도 저장된 결과를 돌려준다(카드 값은 저장하지 않는다).
- callback 서명은 검증하지 않고 PG 조회로 검증한다(로컬 실습 한정). 회로 수치는 실측하지 않은 가설이다.

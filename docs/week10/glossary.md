# Week 10 용어집

| 용어 | 정의 | 코드 이름 |
| --- | --- | --- |
| 결제 시도 | 한 주문에 저장되는 PG 승인 요청 단위. 재요청·대사는 저장된 payment key, attempt key, provider order ID, 요청 금액으로 연결한다. | `Payment` |
| payment attempt key | commerce가 한 결제 시도를 식별하고 최초 승인과 동일 요청을 연결하는 멱등 키. | `paymentAttemptKey` |
| provider order ID | PG가 해당 결제 거래를 내부 주문과 연결할 때 사용하는 합성 주문 식별자. | `providerOrderId` |
| 거래 키 | PG가 저장한 승인 거래를 식별하는 식별자. 응답 유실 후에도 권위 조회 결과에서 확인한다. | `transactionKey` |
| 결제 미확정 주문 | PG 결제 상태가 `PENDING` 또는 `UNKNOWN`이고 주문이 아직 `CONFIRMED`가 아닌 주문. | `PaymentStatus`, `OrderStatus` |
| 응답 유실 | PG fixture가 승인 요청을 받은 뒤 SUCCESS 거래를 기록했으나 commerce가 승인 응답을 받지 못한 경계. | `TossPgFixture` fault control |
| 권위 거래 조회 | 저장된 payment key로 PG의 기존 거래를 읽는 작업. 거래 생성이나 commerce 상태 변경을 하지 않는다. | `PaymentGateway.findByPaymentKey` |
| reconcile | 본인 주문의 저장된 결제 시도를 PG에서 조회하고 검증된 최종 결과를 기존 주문 결제 트랜잭션에 적용하는 작업. | `POST .../payment/reconcile` |
| 상관 로그 | `orderId`와 `paymentAttemptKey`를 중심으로 adapter 조회, 내부 결과 적용, 예약·Outbox 결과를 연결하는 합성 환경의 구조화 기록. | 결제 application log |
| 미확정 결제 gauge | DB에서 마지막 갱신 시점에 읽은 `PENDING + UNKNOWN` 주문 수. 최대 5초 polling 지연을 갖는다. | `commerce.payment.unresolved` |
| fault 집합 | PG 거래는 SUCCESS지만 응답 유실 때문에 관찰 시점에 commerce 주문이 UNKNOWN인 장애 주문 3건을 포함하는 별도 10건 집합. | 사고 테스트 시나리오 |
| baseline 집합 | 응답 유실이 꺼진 상태에서 정상 승인되고 내부 확정된 별도 10건 주문 집합. | 사고 테스트 시나리오 |
| 사고 증거 | 한 실행의 단계·시각·실제 주문/attempt/PG 거래 키·health/Prometheus 응답·상관 로그·대사 결과를 연결하는 합성 데이터. | `docs/week10/evidence/` |

## 상태 표현

| 내부 주문/결제 | 해석 |
| --- | --- |
| `DRAFT / PENDING` | 승인 경계가 시작됐으나 commerce가 최종 결과를 기록하지 않은 상태 |
| `DRAFT / UNKNOWN` | 응답 또는 결과 조회로 최종 거래 상태를 아직 확인하지 못한 상태 |
| `CONFIRMED / SUCCESS` | 권위 성공 결과를 검증하고 주문·예약·Outbox 경계까지 확정한 상태 |
| `DRAFT / FAILED` | 권위 실패 결과를 반영하고 재고 예약을 반환한 상태 |

PG의 `SUCCESS` 거래 사실과 내부 `UNKNOWN`은 서로 다른 시스템의 상태이므로 함께 존재할 수 있다.

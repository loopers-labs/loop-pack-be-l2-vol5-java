# 주문 확정 시각 필드

| 대상 | 변경 |
| --- | --- |
| 테이블 | `orders` |
| 필드 | `confirmed_at` (`confirmedAt`) |
| 저장 시점 | 주문이 최초로 `CONFIRMED`가 되는 시점 |

최초 확정 시각을 `orders.confirmed_at`에 저장하고, `OrderConfirmed.confirmedAt`에도 DB에 저장된 같은 값을 사용합니다.

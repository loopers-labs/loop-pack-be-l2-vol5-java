# Service API·트랜잭션·잠금 순서

[← 전체 선택 현황](total_trade_off.md)

## 판단할 문제

파사드가 부를 Service 기능을 어떤 형태로 드러낼지, 트랜잭션 경계를 어디에 둘지, 여러 Service에 흩어진 잠금의 순서를 누가 책임질지 정한다.

> **채택 — 기존 Service에 public 메서드, 파사드에만 `@Transactional`, 잠금 순서는 파사드 호출 순서**
>
> | Service | 메서드 | 하는 일 |
> |---|---|---|
> | `OrderService` | `lockForConfirm(orderId)` | 주문 `findByIdForUpdate`, 없으면 `ORDER_NOT_FOUND`, `ensureCanConfirm()` |
> | `OrderService` | `confirm(order)` | `order.confirm()` 후 저장(주문 기록 cascade) |
> | `WalletService` | `lockByUserId(userId)` | 지갑 `findByUserIdForUpdate` |
> | `WalletService` | `pay(wallet, order)` | `wallet.use(주문 합계, 주문 id)`(잔액 검증 포함) 후 지갑·사용 기록 저장 |
> | `ProductService` | `decreaseStocks(quantitiesByProductId)` | 상품 id 오름차순 `findByIdForUpdate`(없으면 `PRODUCT_NOT_FOUND`) → 품목 등장 순서로 모두 검증 → 차감 → 저장 |
>
> - 이 메서드들은 UseCase가 아니며 `@Transactional`을 붙이지 않는다. 파사드의 트랜잭션에 참여한다(R02 [03](../../../week3/r02-order-consistency/trade_off/03-transaction-boundary.md) 유지).
> - 잠금 순서(주문 → 지갑 → 상품 id 오름차순)는 파사드의 호출 순서와 `decreaseStocks` 내부 정렬로 지킨다.
>
> 대신 잠금 순서가 한 클래스(현행 `JpaConfirmOrderWriter`)가 아니라 파사드 호출 순서와 Service 내부 정렬 두 곳에 나뉜다. 파사드 단위 테스트의 `InOrder` 검증으로 고정한다.

## 장단점 비교

| 주제 | 채택 | 미채택 |
|---|---|---|
| API 형태 | **기존 Service에 public 메서드**: 다이어그램과 같고 재사용 진입점이 Service 하나 | 컨텍스트별 전용 컴포넌트(StockManager 등): 클래스 증가 |
| 트랜잭션 | **파사드만 REQUIRED**: R02 결정 유지, 경계가 한 곳 | Service 메서드 MANDATORY: R02에서 미채택. REQUIRED: 경계가 여러 곳에 보임 |
| 잠금 순서 | **파사드 호출 순서**: 확정 흐름을 읽으면 순서가 보임 | 각 Service 내부만 책임: 서비스 간 순서 보장이 없어 데드락 위험 재검토 필요 |

## 함께 고민한 내용

1. **트랜잭션 밖 호출:** 잠금 조회는 트랜잭션이 없으면 실패한다. 이 메서드들은 파사드 같은 트랜잭션 진입점 안에서만 부르도록 주석으로 남긴다. MANDATORY로 강제하는 안은 R02 03에서 이미 버렸다.
2. **오류 우선순위:** 주문 상태(`lockForConfirm`) → 상품 삭제·재고(`decreaseStocks`, 품목 등장 순서) → 잔액(`pay`) 순서로 부르면 현행 정책의 검증 순서와 같다. 상품 잠금은 id 오름차순이지만 검증은 품목 순서로 해서, 여러 상품이 동시에 실패할 때의 오류도 현행과 같다.
3. **ArchUnit:** `application.ordering.facade`가 `application.mall.service`·`application.pay.service`에 의존하는 것은 현재 계층 규칙상 허용된다.

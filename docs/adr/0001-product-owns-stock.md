# ADR 0001: Product가 Stock 값을 소유한다

Status: Accepted
Date: 2026-09-17

## Context

재고에는 0 이상, 양수 차감, 보유량 초과 거절 규칙이 있다. 독립된 Stock Aggregate로 둘지 Product 내부 값으로 둘지 결정해야 했다.

## Decision

Stock은 Product 내부의 불변 값 객체로 둔다. Stock은 새 수량 값을 반환하고 Product가 그 값을 교체한다.

## Options considered

| Option | Benefit | Cost | Why not chosen |
| --- | --- | --- | --- |
| 별도 Stock Aggregate·Repository | 독립 재고 관리에 유리 | 조회·저장·트랜잭션 조율이 증가 | 창고별 재고 등 독립 요구가 아직 없다 |
| Product 내부 Stock VO | 수량 규칙을 분리하면서 Product 상태와 함께 변경 | 독립 재고 기능이 생기면 재검토 필요 | 선택 |

## Consequences

재고 변경은 Product를 통해서만 수행한다. StockRepository를 만들지 않으며, `StockTest`와 Product 테스트가 수량 규칙과 실패 후 상태 보존을 검증한다.

## Evidence

- [설계 규칙](../week2/design.md)
- `apps/commerce-api/src/main/java/com/loopers/domain/product/Stock.java`
- `apps/commerce-api/src/test/java/com/loopers/domain/product/StockTest.java`
- 3주차에 재고 차감의 동시성 보호 방식을 다룬 [ADR 0012](./0012-stock-decrement-locks-product-row.md)

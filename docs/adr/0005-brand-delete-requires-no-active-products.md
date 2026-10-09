# ADR 0005: 활성 Product가 있으면 Brand 삭제를 거절한다

Status: Superseded by [ADR 0014](./0014-brand-delete-cascades-to-active-products.md)
Date: 2026-09-17

## Context

Product는 재고가 남아 있어도 판매 중단을 위해 논리 삭제할 수 있다. Brand를 삭제했는데 활성 Product가 남으면 고객 상품 조회의 Brand 참조가 깨진다.

## Decision

활성 Product가 하나라도 있으면 Brand 삭제를 `409 Conflict`로 거절한다. 활성 Product가 없을 때만 Brand를 논리 삭제하고 `204 No Content`를 반환한다.

## Options considered

| Option | Benefit | Cost | Why not chosen |
| --- | --- | --- | --- |
| Brand 삭제와 Product 일괄 삭제 | 한 요청으로 정리한다 | 판매 중인 Product를 의도치 않게 중단할 수 있다 | 영향 범위가 너무 크다 |
| Brand만 삭제하고 Product 유지 | Brand 단종 표현이 가능하다 | 활성 Product의 Brand 응답 정책이 필요하다 | 현재 요구가 없다 |
| 활성 Product가 있으면 거절 | 참조 일관성이 명확하다 | 먼저 Product 정리가 필요하다 | 선택 |

## Consequences

BrandFacade가 ProductRepository로 활성 Product 존재를 확인하고, BrandService는 Brand 상태 변경만 수행한다.

## Evidence

- `apps/commerce-api/src/main/java/com/loopers/application/brand/BrandFacade.java`
- `apps/commerce-api/src/test/java/com/loopers/application/brand/BrandFacadeTest.java`
- 이 결정을 대체한 [ADR 0014](./0014-brand-delete-cascades-to-active-products.md)

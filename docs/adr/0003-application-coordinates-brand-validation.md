# ADR 0003: Application이 Brand 검증과 Product 생성을 조율한다

Status: Accepted
Date: 2026-09-17

## Context

상품 등록에는 활성 Brand 확인과 Product 생성이 함께 필요하다. 처음에는 ProductService가 BrandService를 직접 호출했다.

## Decision

ProductFacade가 BrandService로 활성 Brand를 확인한 뒤 ProductService에 생성을 요청한다. ProductService는 Product 생성·저장·조회 규칙만 맡는다.

## Options considered

| Option | Benefit | Cost | Why not chosen |
| --- | --- | --- | --- |
| ProductService가 BrandService 호출 | 구현이 짧다 | Product 규칙과 다른 Aggregate 조회가 섞인다 | Aggregate 협력 조율 책임이 domain service에 남는다 |
| ProductFacade가 순서 조율 | use case 흐름과 Aggregate 경계가 명확하다 | Facade 협력 테스트가 필요하다 | 선택 |

## Consequences

Controller는 HTTP 변환만, Facade는 cross-aggregate 흐름과 트랜잭션 경계를, ProductService는 Product 상태 규칙을 담당한다.

## Evidence

- `apps/commerce-api/src/main/java/com/loopers/application/product/ProductFacade.java`
- `apps/commerce-api/src/test/java/com/loopers/application/product/ProductFacadeTest.java`
- 3주차에 등록·브랜드 변경 경쟁의 잠금 순서를 다룬 [ADR 0013](./0013-product-registration-and-brand-change-lock-order.md)

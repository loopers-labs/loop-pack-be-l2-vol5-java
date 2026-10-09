# ADR 0014: 브랜드 삭제는 연결된 미삭제 상품을 함께 삭제한다

Status: Accepted
Date: 2026-10-09
Supersedes: [ADR 0005](./0005-brand-delete-requires-no-active-products.md)

## Context

[ADR 0005](./0005-brand-delete-requires-no-active-products.md)는 활성 Product가 있으면 Brand 삭제를 거절하기로 했었다. 과제는 브랜드 일괄 삭제를 요구한다 — 브랜드만 삭제되거나 일부 상품만 삭제되면 사용 가능한 상품과 브랜드 상태가 어긋나고, 과거 주문 정보까지 지워져서도 안 된다.

## Decision

`BrandFacade.delete()`에서 브랜드와 연결된 미삭제 상품의 논리 삭제를 한 트랜잭션으로 묶는다. 중간 실패 시 이번 요청의 변경을 모두 되돌리고, 다른 브랜드와 과거 주문은 유지한다. 재고 0인 상품도 삭제 대상에 포함한다.

브랜드는 `FOR UPDATE`로 조회하고 연결 상품도 잠그며 읽는다. 상품 등록은 브랜드에 `FOR SHARE`를 사용해 삭제와 순서를 지킨다 (잠금 순서는 [ADR 0013](./0013-product-registration-and-brand-change-lock-order.md) 참고).

## Consequences

삭제 후에는 고객 상품·브랜드 상세 조회가 없는 대상 오류를 반환하고, 상품·내 좋아요 목록에서 제외된다. 새 좋아요·새 주문·기존 DRAFT 확정·상품 수정·재고 변경은 거절되며, 남아 있는 자기 좋아요 취소와 과거 주문 조회는 유지된다. 이전에는 활성 상품이 있으면 삭제 자체가 거절됐지만, 이제는 삭제가 상품까지 정리하므로 그 거절 응답은 더 이상 발생하지 않는다.

## Evidence

- [ADR 0005](./0005-brand-delete-requires-no-active-products.md) — 대체된 원 결정
- [3주차 설계 1.1](../week3/design.md#11-브랜드-일괄-삭제)
- [3주차 구현 기록 1.1](../week3/implementation-log.md#11-브랜드-일괄-삭제)

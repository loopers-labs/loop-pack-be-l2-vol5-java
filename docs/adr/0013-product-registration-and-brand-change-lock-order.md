# ADR 0013: 상품 등록은 브랜드 공유 잠금, 브랜드 변경은 변경 잠금을 사용한다

Status: Accepted
Date: 2026-10-09

## Context

상품 등록 중 브랜드가 삭제되는 것은 막되, 같은 브랜드의 서로 다른 상품 등록까지 줄 세울 필요는 없다고 판단했다. 사고 흐름은 [설계 2.2](../week3/design.md#22-상품-등록과-브랜드-삭제)에 남긴다.

## Decision

상품 등록은 브랜드 `FOR SHARE`, 브랜드 수정·삭제는 `FOR UPDATE`를 사용한다. 조회한 삭제 상태는 코드가 검사하며 잠금은 각 유스케이스 트랜잭션 종료까지 유지한다.

## Alternatives and costs

모두 FOR UPDATE로 처리하면 단순하지만 등록끼리도 기다린다. 일반 조회만 사용하면 확인 이후 삭제를 막지 못한다. 선택한 방식도 브랜드 변경과 등록 사이에는 대기가 생긴다.

연결 상품 잠금 범위는 [ADR 0016](./0016-brand-deletion-locks-products-by-id.md), 검증 결과는 [구현 기록 2.2](../week3/implementation-log.md#22-상품-등록과-브랜드-삭제)에 둔다.

# ADR 0002: 좋아요 수는 관계에서 집계한다

Status: Superseded by [ADR 0020](./0020-like-count-denormalized-counter.md)
Date: 2026-09-17

## Context

고객 상품 조회에는 좋아요 수가 필요하지만, 반복 등록·취소 요청에서 Product의 가변 카운터와 Like 관계가 어긋날 위험이 있다.

## Decision

`likeCount` 컬럼을 Product에 저장하지 않는다. Like 관계를 저장하고 조회 시 `COUNT`로 좋아요 수를 계산한다.

## Options considered

| Option | Benefit | Cost | Why not chosen |
| --- | --- | --- | --- |
| Product에 가변 카운터 저장 | 조회가 단순 | 반복 요청과 관계 상태의 불일치 위험 | 관계 집계가 요구에 충분하다 |
| Like 관계 집계 | 관계가 유일한 사실 원천 | 목록 조회에 집계 쿼리가 필요 | 선택 |

## Consequences

Product는 좋아요 수를 변경하지 않는다. LikeRepository가 관계 저장과 집계를 맡고, ProductFacade가 상품 정보와 집계 결과를 응답으로 조합한다.

## Evidence

- [설계 규칙](../week2/design.md)
- `apps/commerce-api/src/main/java/com/loopers/domain/like/LikeRepository.java`
- week4에서 10만 건 스케일의 데이터 쏠림(좋아요가 한 상품에 몰리는 경우) 집계 비용을 확인하고 이 결정을 대체한 [ADR 0020](./0020-like-count-denormalized-counter.md)

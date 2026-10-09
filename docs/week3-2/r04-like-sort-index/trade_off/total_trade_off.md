# R04 트레이드오프

[요구사항](../requirement.md) · [구현 계획](../plan.md) · [전체 요구사항](../../total_requirement.md) · [주제 문서 템플릿](../../_template.md)

**굵게 = 채택** · ~~취소선 = 미채택~~ · `[검토 전]` / `[검토 중]` = 미확정 · `[보류]` = 필요할 때 재검토

모든 주제를 결정했다(2026-09-30 문답 Q1~Q15). 규모 가정은 활성 상품 약 100만 건, 좋아요 수백만·인기 상품 편중(R03)이다.

## 선택 현황

1. [좋아요 수 저장 위치](01-like-count-location.md): **`products.like_count` 비정규화 + `(deleted, like_count, id)`·`(deleted, brand_id, like_count, id)` 인덱스, `product_like_counts` 제거, 엔티티 읽기 전용 매핑·도메인 미포함** / ~~집계 테이블에 필터 컬럼 복제~~ / ~~집계 테이블 정렬 인덱스만~~ / ~~집계 테이블 유지(두 곳 갱신·집계 원본)~~ / ~~엔티티 미매핑~~ / ~~도메인 `Product.likeCount`~~
2. [반영·재집계와 상품 잠금](02-flush-and-recount.md): **5초 반영은 id 오름차순 한 트랜잭션 배치 UPDATE, 전체 재집계는 값이 다른 행만 쓰는 단일 UPDATE JOIN, `updated_at` 유지** / ~~청크 커밋~~ / ~~정렬 없음~~ / ~~0 초기화 + UPDATE JOIN~~ / ~~`updated_at` 갱신~~
3. [목록 전체 개수](03-list-count.md): **COUNT에서 브랜드 조인 제거(목록은 방어 조건 유지), 필터 없는 전체 개수만 `AtomicReference` 30초 캐시, 무효화 없음** / ~~COUNT 유지·조인만 제거~~ / ~~다음 페이지 여부만~~ / ~~개수 전용 테이블~~ / ~~Spring Cache + Caffeine~~
4. [범위와 검증](04-scope-and-verification.md): **좋아요순에 집중(최신순·가격순은 EXPLAIN 확인·기록만), 깊은 페이지는 한계 기록, 로컬 대량 데이터 수동 EXPLAIN, 마이그레이션 순서 문서 기록, 가벼운 테스트** / ~~최신순·가격순 인덱스 추가~~ / ~~2단계 조회~~ / ~~커서 페이지네이션~~ / ~~slow 태그 EXPLAIN 테스트~~ / ~~실행 SQL 파일 추가~~

## 구현 계획으로 이어갈 것

1~4번 모두 [구현 계획](../plan.md)으로 구체화한다.

# R03. JDBC 조회 전환과 좋아요 집계 개선

[전체 요구사항](../total_requirement.md) · 작업 브랜치: `volume-3/r03-jdbc-and-like-aggregation` · PR 대상: `volume-3/main`

상태: 구현·검증 완료([결과](result.md)). 최종 check: test 236건, slowTest 17건 통과. refacto 병합 후 main 기준으로 리베이스, [PR #16](https://github.com/corinB/loop-pack-be-l2-vol5-java/pull/16) 리뷰 중. 선택 현황은 [트레이드오프 전체 목록](trade_off/total_trade_off.md)을 따른다.
기준 항목: 데이터 접근 기준(조회 QueryDSL·쓰기 JPA·배치 JDBC), 분석 항목 1(좋아요 전체 재집계), 항목 5(좋아요 등록 3단계).

## 1. 목적

조회는 QueryDSL, 쓰기는 JPA로 통일해 데이터 접근 도구를 줄이고, JPA 쓰기 트랜잭션 안에서 JDBC가 flush되지 않은 변경을 놓치는 위험을 없앤다.
JDBC로 남는 유일한 영역인 좋아요 집계는 매 주기 전체를 다시 세는 비용을 줄인다.

## 2. 현재 동작과 변경점

| 구분 | 현재 코드에서 확인한 동작 | 요구하는 변경 |
|---|---|---|
| 단순 조회 DAO | `infrastructure/query/mall/JdbcBrandQueryDao`, `JdbcProductLikeCountQueryDao`, `query/pay/JdbcWalletQueryDao`, `query/shopping/JdbcUserQueryDao`가 JdbcClient로 SQL 문자열 실행 | QueryDSL Projection으로 전환. `application` 계약과 View는 유지 |
| 조인·페이징 조회 DAO | `query/ordering/JdbcOrderQueryDao`(주문 헤더 페이지 → 품목 `IN` 조회 2단계), `query/shopping/JdbcLikeQueryDao`(좋아요·상품·브랜드·집계 조인) | QueryDSL로 전환하되 헤더·품목 2단계 구조와 정렬·페이징 결과 유지 |
| 좋아요 등록·취소 | `infrastructure/dao/shopping/JdbcLikeCommandDao`가 `EXISTS` → `INSERT` → 중복 예외 시 재확인. `LikeController`가 DAO를 직접 호출 | JPA로 전환. 중복 등록의 멱등성 유지 |
| 좋아요 집계 | `LikeCountAggregationScheduler`가 10초마다 `LikeCountAggregationService` 실행. `JdbcLikeCountAggregationDao`가 전체 카운트를 0으로 초기화한 뒤 `product_likes` 전체를 `GROUP BY`로 재집계 | JDBC를 유지하면서 한 주기 비용이 전체 좋아요 수에 비례하지 않도록 개선 |

## 3. 포함·제외 범위

**포함**

- 위 조회 DAO의 QueryDSL 전환과 기존 조회 결과·정렬·페이징의 동일성 확인. `JdbcProductLikeCountQueryDao`는 전환하지 않고, 상품 쓰기 응답을 조회 경로로 옮기면서 제거한다([결정](trade_off/04-query-conversion.md)).
- 좋아요 등록·취소의 JPA 전환과 동시 중복 등록의 멱등성 확인.
- 좋아요 집계 방식 변경과 집계 결과의 정확성 확인.
- 전환 후 운영 코드에서 JdbcClient가 집계 DAO에만 남았는지 확인.

**제외**

- 좋아요순 목록 정렬·인덱스(R04), 주문 확정 잠금(R05), repository 저장 경로(R06).
- 이미 QueryDSL인 `QueryDslProductQueryDao`의 쿼리 변경.
- 좋아요 API의 HTTP 계약 변경.

## 4. 규칙

- 조회 DAO는 엔티티가 아니라 Projection으로 Row·View를 만든다. 역방향 `@OneToOne`(주문 → 주문 기록)의 즉시 로딩과 N+1을 피하기 위해서다.
- `application` 계층의 QueryDao·dao 계약과 View·Row 타입의 공개 형태는 유지한다. ArchUnit 의존 방향을 바꾸지 않는다.
- 같은 사용자·상품의 좋아요는 한 행만 존재한다. 이미 등록된 좋아요를 다시 등록해도 성공하며 행이 늘지 않는다.
- 집계 DAO의 JdbcClient는 JPA 쓰기와 같은 트랜잭션에서 실행하지 않는다.
- 좋아요가 모두 취소된 상품의 집계 값은 다음 집계 이후 0이 된다(week2 [읽기 모델](../../week2/06-read-models.md) 규칙 유지).

## 5. 시나리오와 기대 결과

| 시나리오 | 사전 상태 | 실행 | 기대 결과 | 유지되어야 할 상태 |
|---|---|---|---|---|
| 조회 전환 회귀 | 브랜드·상품·주문·지갑·좋아요 fixture | 기존 조회 API 호출 | 응답 본문·정렬·페이지 정보가 전환 전과 같음 | 기존 E2E·조회 테스트 기대값 |
| 주문 목록 페이징 | 품목이 여러 개인 주문 여러 건 | 주문 목록 페이지 조회 | 헤더 페이지 쿼리 1회 + 품목 조회 1회, 페이지 크기 정확 | 메모리 페이징 없음 |
| 좋아요 중복 등록 | 좋아요 없음 | 같은 사용자·상품으로 동시 등록 여러 번 | 모두 성공, 좋아요 행 1개 | 트랜잭션 롤백·기술 오류 없음 |
| 좋아요 취소 | 좋아요 1개 | 취소 | 행 삭제, 없는 좋아요 취소도 성공 | 기존 취소 계약 |
| 집계 정확성 | 좋아요 등록·취소가 섞인 상태 | 집계 실행 | 상품별 값이 실제 좋아요 수와 같음, 모두 취소된 상품은 0 | 변경이 없는 상품의 값 |
| 집계 비용 | 좋아요가 많은 상품 다수 중 일부만 변경 | 집계 실행 | 선택한 방식의 비용 기준(트레이드오프에서 확정)을 만족 | 조회와의 잠금 경합이 늘지 않음 |

## 6. 완료 조건

- 운영 코드의 JdbcClient 사용처가 좋아요 집계 DAO로 한정된다.
- 위 시나리오의 테스트가 실제 MySQL fixture에서 통과하고, 전환 전후 실행 SQL·쿼리 수를 `result.md`에 기록한다.
- `./gradlew :apps:commerce-api:check`가 통과한다. Checkstyle·ArchUnit 규칙과 기존 테스트 기대값을 완화하지 않는다.

## 7. 미정 사항

트레이드오프 문답으로 결정한다. 아래는 후보 목록이며 채택을 의미하지 않는다.

- ~~좋아요 등록의 JPA 구현~~ → 결정: [중복 등록·취소 처리](trade_off/02-like-duplicate.md).
- ~~좋아요 등록·취소의 계층 위치~~ → 결정: [도메인 구조](trade_off/01-like-aggregate.md), [활성 상품 확인](trade_off/03-like-product-check.md).
- ~~좋아요 집계 방식~~ → 결정: [집계 전략](trade_off/05-like-count-strategy.md), [변경 추적](trade_off/07-like-change-tracking.md), [추가·삭제 감지](trade_off/08-like-insert-detection.md), [반영과 전체 재집계](trade_off/09-like-count-flush.md).
- ~~`product_like_counts` 행 생성 시점~~ → 결정: 반영 upsert가 행이 없으면 만든다([09](trade_off/09-like-count-flush.md)).
- ~~테스트 코드의 JdbcClient 허용 여부~~ → 결정: [조회 전환 방식](trade_off/04-query-conversion.md#테스트-코드의-jdbcclient-허용-6번).

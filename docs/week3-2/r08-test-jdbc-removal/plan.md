# R08 구현 계획

[요구사항](requirement.md) · [트레이드오프](trade_off/total_trade_off.md) · [전체 요구사항](../total_requirement.md)

작업 브랜치: `volume-3/r08-test-jdbc-removal` · PR 대상: `volume-3/main`

상태: 사용자 합의 완료(2026-10-09), Sonnet 위임. R07 브랜치(PR #19)에서 분기했다. R07이 병합되면 main 위로 리베이스한 뒤 PR을 만든다.

## 문서와 진행 원칙

- 결정 근거는 [01 데이터 준비·검증](trade_off/01-test-data-access.md), [02 범위·헬퍼·규칙](trade_off/02-scope-and-guard.md)을 따른다.
- 커밋 메시지는 `type: 한국어 한 문장`이며 Co-Authored-By 등 AI 표기 줄을 붙이지 않는다.
- 테스트의 **단언 기대값은 바꾸지 않는다.** 바꾸는 것은 준비·검증 수단(JdbcClient → QueryDSL·저장소)과, 고정 id를 생성 id 변수로 바꾸는 것뿐이다. 삭제하는 테스트는 `LikeStorageIntegrationTest` 하나다.
- 운영 코드(`src/main`)는 바꾸지 않는다. Checkstyle·ArchUnit을 완화하지 않는다. 새 파일 첫 줄에는 한국어 역할 주석을 단다. push·PR은 하지 않는다.

## 대체 방식

| 용도 | 이전 | 이후 |
|---|---|---|
| 검증 조회 | `jdbcClient.sql("SELECT COUNT(*) FROM point_bills WHERE …").query(Long.class).single()` | `queryFactory.select(POINT_BILL.count()).from(POINT_BILL).where(…).fetchOne()` (`JPAQueryFactory` 주입) |
| 막아 둔 컬럼 | `UPDATE products SET like_count = ?`, `UPDATE orders SET created_at = ?` | `queryFactory.update(PRODUCT).set(PRODUCT.likeCount, n).where(…).execute()` — 트랜잭션이 없으면 `TransactionTemplate`으로 감싼다 |
| 원시 INSERT | `INSERT INTO products (id, …)`, `INSERT INTO product_likes …` | `ProductRepository.save(Product.create(…))`, `LikeRepository.save(Like.create(…))`, 생성 id 사용 |
| 트랜잭션 안 검증 | — | 조회 전에 `entityManager.flush()`·`clear()` |

## 커밋별 구현 순서

### 커밋 1 — 운영 코드 JDBC 의존 ArchUnit 규칙

- [x] `LayerArchitectureTest`에 규칙 추가: `noClasses().that().resideOutsideOfPackage("com.loopers.infrastructure.dao..").or().haveSimpleNameNotStartingWith("Jdbc").should().dependOnClassesThat().resideInAPackage("org.springframework.jdbc..")` 형태(정확한 DSL은 ArchUnit API에 맞춘다). 테스트 클래스는 기존 `DoNotIncludeTests`로 제외된다.
- [x] 규칙이 실제로 잡는지 확인: 운영 클래스 하나에 `JdbcClient` 필드를 임시로 넣어 실패를 보고 되돌린다(커밋하지 않음). 결과를 검증 기록에 적는다.
- [x] `DataSourceConfig`(`modules/jpa`)는 `com.loopers.config..`라 `org.springframework.jdbc`에 의존하면 규칙에 걸릴 수 있다. 걸리면 규칙 대상을 `apps/commerce-api`의 계층 패키지(`com.loopers.domain..`, `application..`, `interfaces..`, `infrastructure..`)로 한정한다.
- [x] 커밋: `test: 운영 코드의 JDBC 의존을 배치 DAO로 제한하는 아키텍처 규칙 추가`

### 커밋 2 — 주문 확정 검증 공용 헬퍼와 주문·브랜드 롤백 테스트

- [x] `com.loopers.support.test`에 공용 헬퍼(예: `OrderConfirmAssertions` 또는 `@Component` 빈 `OrderConfirmProbe`)를 만든다. 제공: 포인트 사용 기록 수(사용자·주문 기준), 주문 상태, 주문 기록 수(상태 기준). 내부는 `JPAQueryFactory`.
- [x] 대상: `ConfirmOrderIntegrationTest`, `ConfirmOrderSqlRollbackIntegrationTest`, `ConfirmOrderConcurrencyIntegrationTest`, `DeleteBrandRollbackIntegrationTest`(주문 상태 조회). 그 밖의 단건 조회(충전 금액 합계 등)는 각 클래스 private 메서드로 QueryDSL.
- [x] 커밋: `test: 주문 확정 검증을 QueryDSL 공용 헬퍼로 바꾸고 JDBC 조회 제거`

### 커밋 3 — shopping 테스트

- [x] `LikeCountAggregationIntegrationTest`: 상품·좋아요를 저장소로 만들고 생성 id를 쓴다. `like_count` 준비·조회와 상품 수 조회는 QueryDSL. "없는 상품 id 무시" 케이스는 존재하지 않는 id(생성 id와 겹치지 않는 값)를 쓴다.
- [x] `LikeRepositoryIntegrationTest`, `QueryDslLikeQueryDaoIntegrationTest`, `LikeApiE2ETest`, `LocalUserFixtureInitializerIntegrationTest`: 검증 조회·`like_count` 준비를 QueryDSL로, 좋아요 원시 INSERT는 `LikeRepository.save`로.
- [x] `LikeStorageIntegrationTest` 삭제.
- [x] 커밋: `test: 좋아요·사용자 테스트의 JDBC 준비·검증을 QueryDSL과 저장소로 교체`

### 커밋 4 — mall·ordering·pay 조회·E2E 테스트

- [x] `QueryDslProductQueryDaoIntegrationTest`, `ProductApiE2ETest`(`like_count`·`created_at` 준비, 상품 수), `QueryDslOrderQueryDaoIntegrationTest`(`created_at` 준비), `OrderApiE2ETest`(주문 수), `WalletApiE2ETest`(충전 기록 수).
- [x] `git grep -nE "JdbcClient|JdbcTemplate" apps/commerce-api/src/test` 결과가 ArchUnit 규칙 정의 외에는 없어야 한다.
- [x] 커밋: `test: 상품·주문·지갑 테스트의 JDBC 준비·검증을 QueryDSL로 교체`

### 커밋 5 — 문서

- [x] `CLAUDE.md`(JdbcClient는 테스트 준비·검증에도 쓴다는 문장, 새 ArchUnit 규칙), `docs/test/*.md`(JdbcClient 언급·`LikeStorageIntegrationTest` 행·개수), R03 [04](../r03-jdbc-and-like-aggregation/trade_off/04-query-conversion.md) §6에 R08 후속 결정 안내, `AGENTS.md`에 관련 문장이 있으면 갱신.
- [x] 검증 기록 작성.
- [x] 커밋: `docs: 테스트 JDBC 제거와 아키텍처 규칙을 관련 문서에 반영`

## 검증 계획

- 커밋마다 바꾼 테스트 클래스만 `--tests`로 실행하고 Checkstyle을 실행한다.
- 마지막에 `./gradlew :apps:commerce-api:check`(test 태스크 한 번으로 slow 포함 전체)를 실행하고 건수·실패·skip을 기록한다. 기대 건수는 현재 260건에서 `LikeStorageIntegrationTest`의 테스트 수를 빼고 ArchUnit 규칙 1건을 더한 값이다.
- **중단 조건:** QueryDSL `update`가 `updatable = false`·`insertable = false` 컬럼을 갱신하지 못하거나 예외가 나면, 다른 수단(native 쿼리 등)으로 바꾸지 말고 보고한다. 기대값을 바꿔야만 통과하는 테스트가 생겨도 멈추고 보고한다.

## 완료 체크리스트

- [x] 테스트 코드의 `JdbcClient`·`JdbcTemplate` 사용 없음
- [x] 운영 코드 JDBC 의존 ArchUnit 규칙 추가, 위반 시 실패 확인
- [x] 기존 테스트 기대값 변경 없음
- [x] `./gradlew :apps:commerce-api:check` 통과

## 검증 기록

- 커밋 1 위험 규칙 확인: `BrandService`에 `JdbcClient` 필드를 임시로 넣고 `com.loopers.architecture.*`를 실행하니 `LayerArchitectureTest.JDBC_DEPENDENCY_RULE`이 실패했다. 되돌린 뒤(커밋하지 않음) 8건 모두 통과. `DataSourceConfig`는 `com.loopers.config..`라 규칙 대상(`domain`·`application`·`interfaces`·`infrastructure` 계층 패키지)에 포함되지 않아 범위 조정이 필요 없었다. 규칙은 `Jdbc`로 시작하는 클래스(`JdbcLikeCountAggregationDao`)만 예외로 둔다.
- QueryDSL `update`로 `products.like_count`(insertable/updatable=false)와 `orders.created_at`·`products.created_at`·`product_likes.created_at`(updatable=false)를 갱신할 수 있음을 확인했다(증감분 7 = 5 + 2 등 기존 기대값 통과).
- `./gradlew :apps:commerce-api:check`: BUILD SUCCESSFUL. `build/test-results/test/*.xml` 합계 260건, 실패 0, 오류 0, skip 0 (260 - `LikeStorageIntegrationTest` 1 + ArchUnit 규칙 1 = 260). `test` 태스크가 slow 태그 포함 전체를 실행한다.
- `git grep -nE "JdbcClient|JdbcTemplate" apps/commerce-api/src/test` 결과: 없음(0건).

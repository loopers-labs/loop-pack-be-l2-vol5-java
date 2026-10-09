# R08. 테스트 코드의 JDBC 제거

[전체 요구사항](../total_requirement.md) · 작업 브랜치: `volume-3/r08-test-jdbc-removal` · PR 대상: `volume-3/main`

상태: 구현·검증 완료([결과](result.md)). 최종 check 260건 통과. 브랜치는 R07 브랜치(PR #19)에서 분기했고 R07 병합(PR #19) 후 main 기준으로 리베이스했다. [PR #20](https://github.com/corinB/loop-pack-be-l2-vol5-java/pull/20) 병합 완료.
기준 자료: 2026-10-09 JDBC 사용처 조사, 사용자 결정 "JDBC는 배치성 작업 외에는 웬만하면 쓰지 않는다".

## 1. 목적

`JdbcClient`·`JdbcTemplate`을 배치 작업(좋아요 수 반영 DAO)에만 남긴다. 테스트의 데이터 준비·검증을 QueryDSL과 도메인 저장소로 바꾸고, 운영 코드에 JDBC가 다시 들어오지 않게 ArchUnit으로 막는다.
R03에서 정한 "테스트 코드의 JdbcClient 허용"([04 §6](../r03-jdbc-and-like-aggregation/trade_off/04-query-conversion.md#테스트-코드의-jdbcclient-허용-6번))을 대체한다.

## 2. 현재 동작과 변경점

| 구분 | 현재 | 변경 |
|---|---|---|
| 운영 코드 | `JdbcLikeCountAggregationDao` 하나만 JDBC 사용 | 유지. ArchUnit 규칙으로 고정 |
| 테스트 검증 조회(11개 클래스) | `JdbcClient`로 `SELECT COUNT(*)`·`SELECT status` 등 | 테스트에서 `JPAQueryFactory`로 조회 |
| 막아 둔 컬럼 준비(4개 클래스) | `UPDATE products SET like_count`, `UPDATE orders SET created_at` | QueryDSL `update` |
| 원시 INSERT(3개 클래스) | 고정 id 상품, `product_likes` 직접 삽입 | 도메인 저장소로 생성, 생성 id 사용 |
| `LikeStorageIntegrationTest` | 원시 INSERT로 유니크 제약 검증 | 삭제(`LikeRepositoryIntegrationTest`가 중복 등록 1행·false로 증명) |

## 3. 포함·제외 범위

**포함**: `apps/commerce-api/src/test`의 `JdbcClient`·`JdbcTemplate` 사용 15개 클래스, 주문 확정 검증 공용 헬퍼, 운영 코드 JDBC 의존 ArchUnit 규칙, 관련 문서.

**제외**: `DatabaseCleanUp`의 `createNativeQuery` TRUNCATE, 운영 코드 `LikeJpaRepository`의 native `INSERT IGNORE`(JDBC가 아닌 JPA native 쿼리), 테스트 코드에 대한 ArchUnit 규칙.

## 4. 규칙

- 테스트의 단언 기대값은 바꾸지 않는다. 고정 id를 쓰던 테스트는 생성 id를 변수로 받되 같은 상황을 검증한다.
- 검증 조회는 영속성 컨텍스트가 아니라 커밋된 값을 봐야 한다. 같은 트랜잭션 안에서 검증하는 테스트는 조회 전에 `flush`·`clear`한다.
- 운영 코드에서 `JdbcClient`·`JdbcTemplate`에 의존할 수 있는 클래스는 `com.loopers.infrastructure.dao..`의 `Jdbc*` 클래스뿐이다.

## 5. 완료 조건

- `git grep -nE "JdbcClient|JdbcTemplate" apps/commerce-api/src/test`의 결과가 ArchUnit 테스트(규칙 정의) 외에는 없다.
- 새 ArchUnit 규칙이 통과하고, 일부러 위반하면 실패함을 확인한다.
- `./gradlew :apps:commerce-api:check`가 통과한다. Checkstyle·ArchUnit 규칙과 기존 테스트 기대값을 완화하지 않는다.

## 6. 미정 사항

모두 [트레이드오프](trade_off/total_trade_off.md)에서 결정했다.

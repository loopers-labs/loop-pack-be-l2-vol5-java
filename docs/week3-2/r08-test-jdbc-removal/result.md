# R08 구현 결과

[요구사항](requirement.md) · [트레이드오프](trade_off/total_trade_off.md) · [구현 계획](plan.md) · [전체 요구사항](../total_requirement.md)

작업 브랜치: `volume-3/r08-test-jdbc-removal` (R07 브랜치에서 분기, R07 병합(PR #19) 후 main 위로 리베이스, 트리 동일) · PR 대상: `volume-3/main`

상태: 구현·검증 완료. 이제 저장소 전체에서 `JdbcClient`·`JdbcTemplate`을 쓰는 코드는 `JdbcLikeCountAggregationDao` 하나뿐이고, ArchUnit 규칙이 이를 고정한다.

## 1. `JdbcLikeCountAggregationDao`를 JDBC로 남긴 이유

R08의 기준은 "JDBC는 배치성 작업에만"이다. 이 DAO는 그 예외에 해당하는 유일한 클래스이며, 아래 이유로 JPA·QueryDSL로 바꾸지 않는다.

이 DAO가 하는 일은 두 가지다.

| 메서드 | 호출 시점 | SQL |
|---|---|---|
| `addDeltas(Map<Long, Long>)` | 5초마다(`LikeCountAggregationScheduler` → `LikeCountDeltaFlushService`) | `UPDATE products SET like_count = GREATEST(0, like_count + ?) WHERE id = ?` 를 상품 id 오름차순으로 `JdbcTemplate.batchUpdate` |
| `recountAll()` | 앱 시작 시 1회, 웹 서버가 뜨기 전(`LikeCountStartupAggregator`) | `UPDATE products p LEFT JOIN (SELECT product_id, COUNT(*) c FROM product_likes GROUP BY product_id) a ON a.product_id = p.id SET p.like_count = COALESCE(a.c, 0) WHERE p.like_count <> COALESCE(a.c, 0)` |

### 1) `like_count`는 JPA가 쓰지 않도록 막아 둔 컬럼이다

`ProductJpaEntity`는 `like_count`를 `insertable = false, updatable = false`로 매핑한다(R04 [01](../r04-like-sort-index/trade_off/01-like-count-location.md)). 재고 차감·상품 수정이 상품 엔티티를 저장할 때, 읽은 시점의 옛 좋아요 수로 5초 반영 값을 덮어쓰지 못하게 하려는 것이다.
그래서 이 컬럼은 엔티티 상태 변경(dirty checking)으로는 바꿀 수 없고, SQL 문장으로만 바꾼다. 엔티티로 바꾸려면 이 보호를 풀어야 한다.

### 2) 현재 값을 읽지 않고 DB에서 바로 더한다

`like_count = GREATEST(0, like_count + ?)`는 읽기 없이 DB 안에서 증감한다. 엔티티 방식이라면 상품마다 "읽기 → 더하기 → 쓰기"가 되어 SELECT가 상품 수만큼 붙는다. 같은 상품을 두고 다른 갱신과 겹치면 값이 유실되지 않도록 잠금도 걸어야 한다.
`GREATEST(0, …)`로 음수를 막는 것도 같은 문장 안에서 처리된다.

### 3) 바뀐 상품 전체를 한 번에 보낸다

5초 동안 바뀐 상품은 많을 때 수천 개다. `JdbcTemplate.batchUpdate`는 이를 JDBC 배치 한 번으로 보내고, 데이터소스 설정 `rewriteBatchedStatements: true`(`modules/jpa/src/main/resources/jpa.yml`)로 드라이버가 왕복 수를 줄인다.
JPQL·QueryDSL `update(...).execute()`나 native 쿼리는 호출할 때마다 문장 하나를 즉시 실행하므로 상품 수만큼 DB 왕복이 생긴다. Hibernate의 쓰기 배치(`hibernate.jdbc.batch_size`)는 엔티티 flush에만 적용되고 일괄 UPDATE 쿼리에는 적용되지 않는다.

### 4) 전체 재집계 SQL은 JPQL로 쓸 수 없다

`recountAll`은 `UPDATE … LEFT JOIN (파생 테이블 GROUP BY) … SET … WHERE 값이 다른 행만`이다. JPQL·QueryDSL의 UPDATE는 조인과 FROM 절 서브쿼리(파생 테이블)를 지원하지 않는다. JPA로 옮기면 native 쿼리가 되어 SQL 문자열이라는 점은 같고, 배치 작업을 JDBC로 둔다는 기준에서 벗어날 이유도 없다.
100만 행을 한 문장으로 처리해 값이 다른 행만 쓰는 것이 이 SQL의 목적이다(R04 [02](../r04-like-sort-index/trade_off/02-flush-and-recount.md)).

### 5) JPA 쓰기와 트랜잭션을 섞지 않는다

JDBC는 영속성 컨텍스트를 거치지 않아 flush되지 않은 JPA 변경을 보지 못한다. 그래서 전체 요구사항의 데이터 접근 기준은 "배치는 JDBC, 단 JPA 쓰기와 같은 트랜잭션에서 쓰지 않는다"로 정했다.
이 DAO를 부르는 곳은 두 군데뿐이고 둘 다 이 조건을 지킨다.
- **반영 트랜잭션:** `LikeCountDeltaFlushService.execute`의 트랜잭션은 버퍼를 비우고 `addDeltas`를 부르는 일만 한다.
- **시작 시 재집계:** 요청을 받기 전에 실행되어 다른 쓰기와 겹치지 않는다.

### 6) 잠금 순서를 직접 정한다

`addDeltas`는 상품 id 오름차순(`TreeMap`)으로 문장을 보낸다. 주문 확정(R02)도 상품을 id 오름차순으로 잠그므로, 두 작업이 같은 상품들을 동시에 잡아도 순환 대기(데드락)가 생기지 않는다(R04 [02](../r04-like-sort-index/trade_off/02-flush-and-recount.md)). 문장 순서를 그대로 지키는 JDBC 배치라서 이 보장을 코드에서 바로 확인할 수 있다.

### 이 예외를 어떻게 고정했나

`LayerArchitectureTest`에 `JDBC_DEPENDENCY_RULE`을 추가했다.

```java
noClasses().that().resideInAnyPackage("com.loopers.domain..", "com.loopers.application..",
        "com.loopers.interfaces..", "com.loopers.infrastructure..")
    .and().haveSimpleNameNotStartingWith("Jdbc")
    .should().dependOnClassesThat().resideInAPackage("org.springframework.jdbc..");
```

- 계층 패키지 안에서 `org.springframework.jdbc`에 의존할 수 있는 운영 클래스는 이름이 `Jdbc`로 시작하는 클래스뿐이고, 현재 `JdbcLikeCountAggregationDao` 하나다.
- `BrandService`에 `JdbcClient` 필드를 잠시 넣으면 이 규칙이 실패함을 확인했다(에이전트, 검토 때 직접 한 번 더). 되돌린 뒤 통과했다.
- 커넥션 풀 설정(`modules/jpa`의 `DataSourceConfig`)은 계층 패키지가 아니라 규칙 대상이 아니다.
- 새 배치 DAO가 JDBC를 써야 한다면 `infrastructure.dao`에 `Jdbc*` 이름으로 두고, 위 1)~6) 같은 이유를 문서에 남긴다.

## 2. 테스트 정리 요약

테스트 14개 클래스의 `JdbcClient` 사용을 QueryDSL(`JPAQueryFactory`)·도메인 저장소로 바꾸고, `LikeStorageIntegrationTest`를 삭제했다. 단언 기대값은 바꾸지 않았다. 반복되던 주문 확정 검증은 `support.test.OrderConfirmProbe`로 모았다. 막아 둔 컬럼(`like_count`, `created_at`)은 QueryDSL `update`로 준비하며, 일괄 UPDATE라 `insertable/updatable = false` 제한을 받지 않는다.
`git grep -nE "JdbcClient|JdbcTemplate" apps/commerce-api/src/test`의 결과는 없다. 세부는 [plan.md 검증 기록](plan.md#검증-기록)에 있다.

## 3. 검증

| 시점 | 결과 |
|---|---|
| 최종(에이전트) | `check` 260건, 실패·오류·skip 0 |
| 검토 후 재실행(직접) | 테스트 결과를 지우고 `check` 재실행, 260건 통과(실패·skip 0). 직전에 규칙 위반을 넣었다 되돌리며 재컴파일이 겹쳐 627초가 걸렸다 |

260건은 기존 260건에서 `LikeStorageIntegrationTest` 1건을 빼고 ArchUnit 규칙 1건을 더한 값이다.

## 4. 계획과의 차이

| 항목 | 계획 | 실제 | 이유 |
|---|---|---|---|
| 공용 헬퍼 범위 | 주문 확정 검증 3종 | `OrderConfirmProbe`(`@Component`)에 주문 확정 테스트들의 합계·충전 금액 조회까지 포함 | 같은 4개 클래스 안에서만 쓰여 함께 둠 |
| 합계 조회 | QueryDSL | 값을 가져와 Java에서 합산 | `NumberPath.sum()` 사용이 컴파일되지 않음(에이전트 보고) |
| 좋아요 저장 | 저장소 | `TransactionTemplate`으로 감싸 저장 | `LikeRepository.save`의 native `INSERT IGNORE`가 트랜잭션을 요구 |

## 5. 한계

- 테스트 코드의 JDBC 재유입은 규칙으로 막지 않는다(사용자 결정). 운영 코드만 ArchUnit으로 막는다.
- 테스트가 인프라 Q타입(JPA 엔티티)을 직접 안다. 이전에도 테이블·컬럼 이름을 알고 있어 결합 수준은 같다.

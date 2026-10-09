# R04 구현 계획

[요구사항](requirement.md) · [트레이드오프](trade_off/total_trade_off.md) · [전체 요구사항](../total_requirement.md)

작업 브랜치: `volume-3/r04-like-sort-index` · PR 대상: `volume-3/main`

상태: 사용자 합의 완료(2026-09-30), Sonnet 위임. 브랜치는 R03 브랜치(`volume-3/r03-jdbc-and-like-aggregation`, PR #16)에서 분기했다. R03 병합(PR #16) 후 main 위로 리베이스했다(트리 동일). 결과는 [result.md](result.md).

## 문서와 진행 원칙

- 결정 근거는 [01 저장 위치](trade_off/01-like-count-location.md), [02 반영·재집계](trade_off/02-flush-and-recount.md), [03 전체 개수](trade_off/03-list-count.md), [04 범위와 검증](trade_off/04-scope-and-verification.md)을 따른다.
- 커밋마다 관련 테스트를 먼저 고치거나 쓴다. 커밋 메시지는 `type: 한국어 한 문장`이며 Co-Authored-By 등 AI 표기 줄을 붙이지 않는다.
- Checkstyle·ArchUnit·기존 테스트 기대값을 완화하지 않는다. 테스트 **데이터 준비** 방법(`product_like_counts` INSERT → `products.like_count` UPDATE)은 테이블이 바뀌므로 고치되, 단언하는 기대값은 바꾸지 않는다.
- 새 파일 첫 줄에는 한국어 역할 주석을 단다(기존 스타일대로 클래스 선언 위).
- 동시성 테스트와 `slow` 태그 테스트는 추가하지 않는다. push·PR은 하지 않는다.

## 변경 책임·구조

| 계층 | 요소 | 변경 |
|---|---|---|
| infrastructure | `ProductJpaEntity` | `like_count` 컬럼 추가: `@Column(name = "like_count", insertable = false, updatable = false, columnDefinition = "bigint not null default 0")`. 인덱스 추가: `idx_products_deleted_like (deleted, like_count DESC, id DESC)`, `idx_products_deleted_brand_like (deleted, brand_id, like_count DESC, id DESC)`. 도메인 `Product`와 `ProductEntityMapper`는 바꾸지 않는다 |
| application | `LikeCountAggregationDao` | `resetAllCounts`·`aggregateAllCounts`를 `recountAll()` 하나로 합친다. `addDeltas(Map<Long, Long>)` 유지 |
| application | `LikeCountAggregationService` | `recountAll()` 1회 호출 |
| infrastructure | `JdbcLikeCountAggregationDao` | `addDeltas`: 상품 id 오름차순으로 정렬한 인자로 `UPDATE products SET like_count = GREATEST(0, like_count + ?) WHERE id = ?` 배치. `recountAll`: 아래 단일 UPDATE JOIN. `updated_at`은 건드리지 않는다 |
| infrastructure | `QueryDslProductQueryDao` | `product_like_counts` 조인 제거, `PRODUCT.likeCount`로 조회·정렬(`likeCount desc, id desc`). COUNT는 브랜드 조인 없이 `products`만. 브랜드 필터가 없을 때만 전체 개수를 TTL 캐시 |
| infrastructure | `QueryDslLikeQueryDao` | `product_like_counts` 조인 제거, `PRODUCT.likeCount` 조회 |
| infrastructure | `ProductLikeCountJpaEntity` | 삭제 |

전체 재집계 SQL:

```sql
UPDATE products p
LEFT JOIN (SELECT product_id, COUNT(*) AS c FROM product_likes GROUP BY product_id) a ON a.product_id = p.id
SET p.like_count = COALESCE(a.c, 0)
WHERE p.like_count <> COALESCE(a.c, 0)
```

### 전체 개수 캐시와 테스트 격리 (문답 이후 발견, 제안대로 합의)

`QueryDslProductQueryDao`는 모든 Spring 테스트가 공유하는 싱글톤이다. 캐시가 켜져 있으면 한 테스트 클래스가 센 전체 개수가 30초 동안 다른 테스트 클래스(`ProductApiE2ETest`의 `totalElements` 등)에 새어 기대값이 흔들린다.

제안:
- TTL을 프로퍼티(`query.product-count-cache.ttl`, 기본 `30s`)로 받고 `test` 프로필에서는 `0s`(캐시 끔)로 둔다. R03의 `scheduler.like-count.enabled=false`와 같은 방식이다.
- 캐시 로직은 작은 infrastructure 클래스(예: `ExpiringCountCache`, `Clock`·TTL·`Supplier<Long>`을 받음)로 분리하고, 캐시 동작은 POJO 테스트 1개로 확인한다(TTL 안이면 재계산 안 함, 지나면 재계산). 문답(Q15)의 "캐시 TTL 단위 테스트 없음"과 달라지는 부분이다. 통합 테스트에서는 캐시가 꺼져 있어 캐시를 확인할 수 없기 때문이다.

## 호출·트랜잭션 경계

```
GET /api/v1/products?sort=likes_desc[&brandId=]
  QueryDslProductQueryDao.findProducts  @Transactional(readOnly)
    목록: products (idx_products_deleted[_brand]_like 순서) ⋈ brands(PK, deleted=false 방어 조건) limit/offset
    개수: brandId 있음 → SELECT COUNT(*) FROM products WHERE deleted=false AND brand_id=?
          brandId 없음 → 캐시(TTL 이내) 또는 SELECT COUNT(*) FROM products WHERE deleted=false

5초마다  LikeCountDeltaFlushService  @Transactional
    buffer.drain() → addDeltas(id 오름차순 배치 UPDATE products)  → 실패 시 restore
앱 시작(웹 서버 전)  LikeCountStartupAggregator → LikeCountAggregationService → recountAll()
주문 확정  products를 id 오름차순 PESSIMISTIC_WRITE (R02, 변경 없음) → 반영과 같은 잠금 순서
```

## 커밋별 구현 순서

### 커밋 1 — EXPLAIN 검증 스크립트와 변경 전 실행 계획

- [x] `docs/week3-2/r04-like-sort-index/explain/`에 스크립트를 둔다. 스크립트는 자체 DDL을 포함해 로컬 Docker MySQL(`docker/infra-compose.yml`)의 별도 스키마에서 실행한다.
  - `before.sql`: 현재 엔티티와 같은 `brands`·`products`(기존 인덱스 2개)·`product_like_counts` DDL.
  - `after.sql`: `products.like_count`와 인덱스 2개를 추가한 DDL.
  - `seed.sql`: 브랜드 1,000개, 상품 100만 건(삭제 약 5%), 좋아요 수는 편중 분포(대부분 0~10, 소수 수만)로 채운다. 재귀 CTE 등 SQL만 사용한다.
  - `explain.sql`: 좋아요순(필터 없음·브랜드 필터), COUNT(필터 없음·브랜드 필터), 최신순·가격순(필터 없음)의 첫 페이지 쿼리를 `EXPLAIN`(가능하면 `EXPLAIN ANALYZE`)으로 실행한다.
- [x] 변경 전 결과(`type`, `key`, `rows`, `Extra`의 `Using filesort`·`Using temporary`, 실행 시간)를 이 문서의 검증 기록에 적는다.
- [x] 커밋: `docs: 좋아요순 조회의 EXPLAIN 검증 스크립트와 변경 전 실행 계획 기록`

### 커밋 2 — 상품에 좋아요 수 컬럼과 정렬 인덱스 추가

- [x] `ProductRepositoryIntegrationTest`에 단언 1개를 먼저 추가한다. JDBC로 `like_count = 5`를 넣고, Repository로 상품을 읽어 재고를 바꿔 저장해도 `like_count`가 5로 남는지 확인한다. 새 상품의 `like_count`가 0인지도 같은 테스트에서 확인한다.
- [x] `ProductJpaEntity`에 컬럼과 인덱스 2개를 추가한다. getter는 필요할 때만 둔다(조회는 QueryDSL `PRODUCT.likeCount` 경로 사용).
- [x] 커밋: `refactor: 상품에 좋아요 수 컬럼과 좋아요순 정렬 인덱스 추가`

### 커밋 3 — 좋아요 수 반영·재집계를 상품 테이블로 전환

- [x] `LikeCountAggregationIntegrationTest`를 먼저 고친다. 상품 행을 먼저 만들고 `products.like_count`로 검증한다.
  - 전체 재집계: 좋아요 수가 저장되고, 좋아요가 사라진 상품의 기존 값은 0이 된다.
  - 증감분: 기존 값에 더하고, 음수는 0으로 막는다. 없는 상품 id의 증감분은 무시된다(예외 없음).
  - "0 초기화 후 집계 실패 시 롤백" 테스트는 두 단계가 한 문장으로 합쳐져 대상 동작이 사라지므로 삭제한다. "집계 행이 없으면 생성" 테스트도 상품 행이 항상 있으므로 삭제한다.
- [x] `LikeCountAggregationServiceTest`가 있으면 `recountAll` 호출로 고친다.
- [x] DAO 계약과 JDBC 구현을 바꾼다. `addDeltas`는 `new TreeMap<>(deltas)` 등으로 id 오름차순을 보장한다.
- [x] 커밋: `refactor: 좋아요 수 반영과 전체 재집계를 상품 테이블 대상으로 전환`

### 커밋 4 — 상품·좋아요 조회를 `products.like_count` 기준으로 전환

- [x] `QueryDslProductQueryDaoIntegrationTest`(`@IntegrationTest`)를 새로 쓴다. 좋아요순에서 좋아요 수 내림차순, 동점은 id 내림차순, 좋아요 0건 상품 포함, 삭제 상품 제외, 브랜드 필터를 확인한다. 좋아요 수는 JDBC `UPDATE products SET like_count`로 준비한다.
- [x] 기존 테스트의 데이터 준비를 `product_like_counts` INSERT에서 `UPDATE products SET like_count`로 바꾼다(`ProductApiE2ETest.saveLikeCount`, `QueryDslLikeQueryDaoIntegrationTest`). 기대값은 바꾸지 않는다.
- [x] 두 QueryDao에서 `product_like_counts` 조인을 없애고 `PRODUCT.likeCount`를 쓴다.
- [x] 커밋: `refactor: 상품·좋아요 조회를 상품의 좋아요 수 컬럼 기준으로 전환`

### 커밋 5 — 목록 개수에서 브랜드 조인 제거와 전체 개수 캐시

- [x] 캐시 POJO 테스트를 먼저 쓴다(TTL 안이면 재계산하지 않음, 지나면 재계산, TTL 0이면 매번 계산).
- [x] `countProducts`에서 브랜드 조인을 제거한다. 브랜드 필터가 없을 때만 캐시를 거친다.
- [x] `application.yml`에 TTL 기본값, `test` 프로필에 `0s`를 둔다.
- [x] 커밋: `refactor: 상품 목록 개수에서 브랜드 조인을 빼고 전체 개수를 짧게 캐시`

### 커밋 6 — 좋아요 집계 테이블 제거

- [x] `ProductLikeCountJpaEntity`를 삭제한다. `LikeStorageIntegrationTest`의 "상품별 집계 저장"(`product_like_counts` 유일 키) 테스트는 테이블이 사라지므로 삭제한다.
- [x] `git grep -n "product_like_counts\|ProductLikeCount" apps/` 결과가 없어야 한다.
- [x] 커밋: `refactor: 상품 좋아요 집계 테이블 제거`

### 커밋 7 — 변경 후 실행 계획과 문서 갱신

- [x] `after.sql`로 같은 EXPLAIN을 실행해 변경 후 결과를 검증 기록에 적는다.
- [x] `docs/test/*.md`의 테스트 목록·개수, `CLAUDE.md`의 좋아요 수 구조 설명(`product_like_counts` → `products.like_count`, upsert → UPDATE)을 갱신한다.
- [x] 커밋: `docs: 좋아요순 조회 변경 후 실행 계획과 관련 문서 갱신`

## 검증 계획

- 커밋마다 `--tests "*Product*"`, `--tests "*Like*"`와 Checkstyle을 실행한다.
- 마지막에 `./gradlew :apps:commerce-api:check`를 실행하고 test·slowTest 건수, 실패·skip을 기록한다. R02의 주문 확정 동시성 테스트(slowTest)가 통과해야 한다.
- EXPLAIN 기대: 좋아요순 두 경우 모두 새 인덱스를 쓰고 `Using filesort`가 없다. 필터 없는 COUNT는 `products` 인덱스만 읽는다. 최신순·가격순(필터 없음)은 결과만 기록한다.
- **중단 조건:** 변경 후 EXPLAIN에서 좋아요순이 새 인덱스를 쓰지 않거나 filesort가 남으면, 인덱스나 쿼리를 임의로 바꾸지 말고 EXPLAIN 결과와 함께 보고한다. `insertable/updatable = false` 매핑으로 스키마 생성이나 저장이 실패해도 멈추고 보고한다.

## 완료 체크리스트

- [x] 좋아요순 목록이 인덱스를 사용하고 전후 EXPLAIN·실행 SQL이 기록됨
- [x] 기존 상품 목록·좋아요·E2E 테스트가 기대값 변경 없이 통과
- [x] JPA 저장이 `like_count`를 덮어쓰지 않음을 테스트로 확인
- [x] `product_like_counts` 참조 없음
- [x] `./gradlew :apps:commerce-api:check` 통과
- [ ] 운영 마이그레이션 순서를 `result.md`에 기록(결과 작성 시)

## 검증 기록

### EXPLAIN 환경

MySQL 8.0.46(`docker-mysql-1`, `docker/infra-compose.yml`). 스크래치 스키마 `r04_before`/`r04_after`. 브랜드 1,000개, 상품 1,000,000건(삭제 50,000건 = 5%, 브랜드당 약 1,000건), `product_like_counts` 610,000행(1% 상품 1만~6만, 나머지 0~10, 약 39% 상품은 집계 행 없음). 스크립트는 [explain/](explain/) 참고(`before.sql` → `seed.sql` → `explain.sql`·`explain_before.sql`, 이후 `after.sql` → `explain.sql`·`explain_after.sql`). 시간은 `EXPLAIN ANALYZE`의 첫 실행(실제 시간) 값이다.

### 변경 전 (커밋 1)

| 쿼리 | type / key | rows(추정) | Extra | 실행 시간 |
|---|---|---|---|---|
| 좋아요순, 필터 없음 | p: ref / `idx_products_deleted_brand_created`(deleted만 사용), b·c: eq_ref PRIMARY | 497,280 (실제 950,000행 읽음) | `Using temporary; Using filesort` | 9,112 ms |
| 좋아요순, 브랜드 필터(500) | b: const, p: ref / `idx_products_deleted_brand_created`(deleted, brand_id), c: eq_ref | 1,000 | `Using temporary; Using filesort` | 34 ms |
| COUNT, 필터 없음(브랜드 조인) | b: ref / `idx_brands_deleted_created`, p: ref / `idx_products_deleted_brand_price` (covering) | 1,000 x 993 | `Using index` | 286 ms |
| COUNT, 브랜드 필터(브랜드 조인) | b: const, p: ref / `idx_products_deleted_brand_price` (covering) | 1,000 | `Using index` | 0.31 ms |
| 최신순, 필터 없음 (기록만) | p: ref / `idx_products_deleted_brand_created`(deleted만 사용) | 497,280 (실제 950,000행 읽음) | `Using filesort` | 5,583 ms |
| 가격순, 필터 없음 (기록만) | p: ref / `idx_products_deleted_brand_created`(가격 인덱스 미사용) | 497,280 (실제 950,000행 읽음) | `Using filesort` | 5,700 ms |

### 변경 후 (커밋 7)

`after.sql`(`products.like_count` + 인덱스 2개, 데이터는 `r04_before`에서 복사)로 만든 `r04_after`에서 `explain_after.sql`·`explain.sql` 실행. 같은 MySQL 8.0.46.

| 쿼리 | type / key | rows(추정) | Extra | 실행 시간 |
|---|---|---|---|---|
| 좋아요순, 필터 없음 | p: ref / `idx_products_deleted_like`, b: eq_ref PRIMARY | 496,944 (LIMIT 20이라 실제 20행만 읽음) | `Using filesort`·`Using temporary` 없음 | 0.30 ms (변경 전 9,112 ms) |
| 좋아요순, 브랜드 필터(500) | b: const, p: ref / `idx_products_deleted_brand_like` | 1,000 (LIMIT 20이라 실제 20행만 읽음) | filesort·temporary 없음 | 0.75 ms (변경 전 34 ms) |
| COUNT, 필터 없음(products만) | p: ref / `idx_products_deleted_brand_price` (covering) | 496,944 (실제 950,000행 스캔) | `Using index` | 177 ms (변경 전 286 ms) |
| COUNT, 브랜드 필터(products만) | p: ref / `idx_products_deleted_brand_price` (covering) | 1,000 | `Using index` | 0.24 ms (변경 전 0.31 ms) |
| 최신순, 필터 없음 (기록만, 변경 없음) | p: ref / `idx_products_deleted_brand_created`(deleted만 사용) | 496,944 (실제 950,000행 읽음) | `Using filesort` | 16,328 ms |
| 가격순, 필터 없음 (기록만, 변경 없음) | p: ref / `idx_products_deleted_brand_created` | 496,944 (실제 950,000행 읽음) | `Using filesort` | 4,522 ms |

- 좋아요순은 두 경우 모두 새 인덱스를 사용하고 filesort가 사라졌다. 기대(중단 조건 미해당)와 일치한다.
- 필터 없는 COUNT는 여전히 인덱스 950,000건을 훑는다(브랜드 조인은 제거됨). 그래서 앱에서 전체 개수를 TTL 캐시한다.
- 최신순·가격순(필터 없음)의 시간은 `brand_id`가 선두 다음 컬럼이라 인덱스를 타지 못해 filesort가 남은 결과이며 이번 범위 밖이다. 변경 전후 시간 차이(5.6s/5.7s → 16.3s/4.5s)는 인덱스가 늘어난 스키마의 버퍼 풀 상태 등 측정 잡음으로 보이며 실행 계획은 동일하다(단발 측정, 반복 측정 아님).
- 이 EXPLAIN의 좋아요순 쿼리는 SQL로 옮긴 것이다. 앱이 실제로 내는 QueryDSL SQL은 `select ... from products p1_0 join brands b1_0 on ... where p1_0.deleted=0 and b1_0.deleted=0 order by p1_0.like_count desc, p1_0.id desc limit ?, ?` 형태이다(Hibernate가 `deleted`를 bit로 비교하므로 동일 계획으로 기대하지만, 앱 실행 로그로 재확인한 것은 아님).

### 최종 검증 (`./gradlew :apps:commerce-api:check`)

- BUILD SUCCESSFUL. `test` 241건 · `slowTest` 17건 (`build/test-results/{test,slowTest}/*.xml` 합산), 실패 0 · 오류 0 · skip 0. slowTest에는 R02 `ConfirmOrderConcurrencyIntegrationTest`가 포함되어 통과했다.
- Checkstyle main/test, ArchUnit 통과(`check`에 포함).
- `git grep -n "product_like_counts\|ProductLikeCount" apps/` 결과 없음.

### 위임 결과 검토 (직접 확인)

- 커밋 7개의 단위·메시지를 확인했고 Co-Authored-By 줄이 없다. 테스트 삭제는 계획의 3개뿐이며 단언 기대값을 바꾼 곳은 없다(데이터 준비만 `UPDATE products SET like_count`로 변경).
- **덮어쓰기 테스트 보강:** `ProductRepositoryIntegrationTest.keepsLikeCountWrittenOutsideJpa`는 외부에서 좋아요 수를 바꾼 **뒤에** 상품을 읽어, 보호 설정이 없어도 최신 값을 다시 써서 통과하는 구조였다. 상품을 먼저 읽고(영속성 컨텍스트에 0) 외부에서 5로 바꾼 뒤 저장하도록 순서를 바꿨다. `insertable = false, updatable = false`를 임시로 빼면 이 테스트가 60번째 줄 단언에서 실패하고, 되돌리면 통과함을 확인했다.
- 재실행 `./gradlew :apps:commerce-api:check`(test 결과를 지우고 실제 실행): BUILD SUCCESSFUL, 2분 38초. test 241건·slowTest 17건, 실패·오류·skip 0.
- 실행 중 Gradle이 `build` 폴더를 지우지 못하는 오류가 두 번 났다(OneDrive·IDE 파일 잠금 추정). 해당 폴더를 직접 지우고 다시 실행해 해결했다. 코드 문제는 아니다.

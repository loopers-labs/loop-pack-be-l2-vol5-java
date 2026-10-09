# R04 구현 결과

[요구사항](requirement.md) · [트레이드오프](trade_off/total_trade_off.md) · [구현 계획](plan.md) · [전체 요구사항](../total_requirement.md)

작업 브랜치: `volume-3/r04-like-sort-index` (R03 브랜치에서 분기, R03 병합(PR #16) 후 `volume-3/main` 위로 리베이스, 리베이스 전후 트리 동일) · PR 대상: `volume-3/main`

상태: 구현·검증 완료. `./gradlew :apps:commerce-api:check`에서 test 241건, slowTest 17건 모두 통과했다(실패·오류·skip 0). Checkstyle·ArchUnit도 통과했다.

## 1. 구현 결과

좋아요 수를 `products.like_count`로 옮기고 정렬 인덱스 2개를 추가해, 좋아요순 목록이 전체 정렬 없이 인덱스 순서로 한 페이지만 읽게 했다. 필터 없는 전체 개수는 브랜드 조인을 빼고 30초 캐시한다.

| 영역 | 이전 | 이후 | 결정 |
|---|---|---|---|
| 좋아요 수 저장 | `product_like_counts` 테이블 | `products.like_count bigint not null default 0`. `ProductJpaEntity`는 `insertable = false, updatable = false`로 매핑해 JPA 저장이 쓰지 않음. 도메인 `Product`에는 없음 | [01](trade_off/01-like-count-location.md) |
| 인덱스 | 최신순·가격순용 2개 | `idx_products_deleted_like (deleted, like_count DESC, id DESC)`, `idx_products_deleted_brand_like (deleted, brand_id, like_count DESC, id DESC)` 추가 | [01](trade_off/01-like-count-location.md) |
| 좋아요순 조회 | `product_like_counts` LEFT JOIN + `coalesce(like_count, 0) desc` | `products.like_count desc, id desc`. 상품 목록·상세·관리자 조회와 내가 좋아요한 목록 모두 LEFT JOIN 제거 | [01](trade_off/01-like-count-location.md) |
| 5초 반영 | `INSERT … ON DUPLICATE KEY UPDATE` 배치 | `UPDATE products SET like_count = GREATEST(0, like_count + ?) WHERE id = ?` 배치, 상품 id 오름차순(주문 확정 잠금 순서와 같음). `updated_at` 유지 | [02](trade_off/02-flush-and-recount.md) |
| 시작 시 재집계 | 0 초기화 + `INSERT … SELECT` 2단계 | `recountAll()` 단일 UPDATE JOIN, 값이 다른 행만 갱신 | [02](trade_off/02-flush-and-recount.md) |
| 목록 개수 | `products ⋈ brands` COUNT 매 요청 | `products`만 COUNT. 브랜드 필터가 없을 때만 `ExpiringCountCache`로 30초 캐시(`query.product-count-cache.ttl`, test 프로필 `0s`). 목록의 브랜드 삭제 조건은 방어용으로 유지 | [03](trade_off/03-list-count.md) |
| 집계 테이블 | `ProductLikeCountJpaEntity` | 삭제 | [01](trade_off/01-like-count-location.md) |

### 호출·SQL

```
GET /api/v1/products?sort=likes_desc[&brandId=]
  목록: products (idx_products_deleted[_brand]_like 순서) ⋈ brands(PK, deleted=false) order by like_count desc, id desc limit
  개수: brandId 있음 → COUNT(products where deleted=false and brand_id=?)
        brandId 없음 → 캐시(30초 이내) 또는 COUNT(products where deleted=false)

5초마다  addDeltas: UPDATE products SET like_count = GREATEST(0, like_count + ?) WHERE id = ?  (id 오름차순 배치)
앱 시작  recountAll:
  UPDATE products p LEFT JOIN (SELECT product_id, COUNT(*) c FROM product_likes GROUP BY product_id) a ON a.product_id = p.id
  SET p.like_count = COALESCE(a.c, 0) WHERE p.like_count <> COALESCE(a.c, 0)
```

## 2. 실행·검증 결과

### EXPLAIN (MySQL 8.0.46, 상품 100만 건 중 활성 95만, 브랜드 1,000개, 1회 측정)

스크립트는 [explain/](explain/)에 있다. 변경 전후 같은 데이터로 측정했다. 상세 표는 [plan.md 검증 기록](plan.md#검증-기록)에 있다.

| 쿼리 | 변경 전 | 변경 후 |
|---|---|---|
| 좋아요순, 필터 없음 | `Using temporary; Using filesort`, 95만 행 읽음, 9,112 ms | `idx_products_deleted_like`, filesort 없음, 20행만 읽음, 0.30 ms |
| 좋아요순, 브랜드 필터 | `Using temporary; Using filesort`, 34 ms | `idx_products_deleted_brand_like`, filesort 없음, 0.75 ms |
| COUNT, 필터 없음 | 브랜드 조인, 286 ms | `products`만, 177 ms (30초에 한 번만 실행) |
| COUNT, 브랜드 필터 | 0.31 ms | 0.24 ms |
| 최신순·가격순, 필터 없음 (변경 없음) | `Using filesort`, 약 5.6초 | 같은 계획(시간은 측정마다 4.5~16초로 흔들림) |

### 테스트

| 시점 | 결과 |
|---|---|
| 커밋별(에이전트) | `*Product*`, `*Like*`, Checkstyle 통과 |
| 최종(에이전트) | `check` test 241, slowTest 17, 실패·오류·skip 0 |
| 검토 후 재실행(직접) | 테스트 결과를 지우고 `check` 재실행, 2분 38초, test 241·slowTest 17 통과 |

핵심 증거는 다음과 같다.
- **덮어쓰기 방지:** `ProductRepositoryIntegrationTest`가 상품을 먼저 읽고 외부에서 `like_count`를 5로 바꾼 뒤 재고를 수정해 저장해도 5가 유지됨을 확인한다. `insertable = false, updatable = false`를 임시로 빼면 이 테스트가 실패함을 확인했다.
- **정렬 규칙:** `QueryDslProductQueryDaoIntegrationTest`가 좋아요 수 내림차순, 동점 id 내림차순, 좋아요 0건 포함, 삭제 상품 제외, 브랜드 필터를 확인한다. 기존 `ProductApiE2ETest`의 좋아요순 기대값은 바꾸지 않았다.
- **반영·재집계:** `LikeCountAggregationIntegrationTest`가 `products.like_count` 기준으로 재집계, 증감 합산, 0 하한, 없는 상품 무시를 확인한다.
- **잔존 참조:** `git grep "product_like_counts\|ProductLikeCount" apps/` 결과 없음.

## 3. 계획과의 차이

| 항목 | 계획 | 실제 | 이유 |
|---|---|---|---|
| 캐시 테스트 | 캐시 TTL 테스트 없음(Q15) | TTL 프로퍼티 + test 프로필 `0s` + `ExpiringCountCacheTest`(POJO 3건) | 계획 작성 중 공유 싱글톤 캐시가 테스트 클래스 사이로 새는 문제를 발견해 합의 후 변경([03](trade_off/03-list-count.md) 6번) |
| EXPLAIN 스크립트 | `before.sql`·`after.sql`·`seed.sql`·`explain.sql` | 전후 공통 `explain.sql`과 스키마별 `explain_before.sql`·`explain_after.sql`로 분리, `after.sql`은 `r04_before` 데이터를 복사 | 전후를 같은 데이터로 비교하기 위해(에이전트 판단) |
| DAO 생성자 | Lombok 생성자 | `QueryDslProductQueryDao`에 명시 생성자(`@Value` TTL) | `@RequiredArgsConstructor`로는 `@Value`를 붙일 수 없음 |
| 테스트 추가 | 계획 외 없음 | "없는 상품 id 증감분 무시" 1건 추가 | UPDATE로 바뀌며 생긴 경계 확인 |
| 덮어쓰기 테스트 | 저장 후 좋아요 수 유지 확인 | 읽기 순서를 바로잡음(커밋 `test: …읽기 순서 수정`) | 에이전트 구현은 외부 갱신 뒤에 읽어 보호 설정 없이도 통과했다. 검토에서 발견해 수정하고 변이 확인 |
| 삭제한 테스트 | 3건 | 3건(0 초기화 롤백, 집계 행 생성, 집계 테이블 유일 키) | 대상 동작이 사라짐. 기대값 완화 없음 |

## 4. 운영 반영 순서 (이번 범위에서는 기록만)

운영 설정은 `ddl-auto: none`이라 스키마를 따로 바꿔야 한다.

1. 새 버전 배포 전: `ALTER TABLE products ADD COLUMN like_count BIGINT NOT NULL DEFAULT 0;`
2. 값 채우기: `UPDATE products p JOIN product_like_counts c ON c.product_id = p.id SET p.like_count = c.like_count;` (새 버전의 시작 시 재집계도 같은 결과를 만들므로 생략 가능하지만, 첫 기동 시간이 길어진다)
3. 인덱스: `CREATE INDEX idx_products_deleted_like ON products (deleted, like_count DESC, id DESC);`, `CREATE INDEX idx_products_deleted_brand_like ON products (deleted, brand_id, like_count DESC, id DESC);`
4. 새 버전 배포.
5. 이전 버전이 모두 내려간 뒤: `DROP TABLE product_like_counts;`

컬럼 추가·인덱스 생성의 잠금 방식(ALGORITHM=INSTANT/INPLACE 가능 여부)은 운영 MySQL 버전에서 확인해야 한다.

## 5. 한계와 후속 검토

- **필터 없는 COUNT:** 여전히 활성 상품 인덱스 약 95만 건을 훑는다(177 ms). 30초에 한 번으로 줄였을 뿐이다.
- **필터 없는 최신순·가격순:** 인덱스가 `(deleted, brand_id, …)` 순서라 전체 정렬이 남는다(수 초). R04 범위 밖으로 두었고, 후속 요구사항 후보다.
- **깊은 페이지:** offset이 크면 건너뛰는 행만큼 읽는다(결정대로 유지).
- **앱 SQL과 EXPLAIN SQL:** EXPLAIN은 QueryDSL이 만드는 SQL을 손으로 옮긴 것이다. 앱이 실제로 내는 SQL로 EXPLAIN하지는 않았다.
- **반영 중 잠금 대기:** 5초 반영 배치가 도는 동안 같은 상품의 주문 확정이 기다릴 수 있다. 대기 시간은 측정하지 않았다.
- **캐시와 서버 여러 대:** 서버마다 따로 캐시하므로 각자 30초 안에 수렴한다.
- **브랜드 삭제 방어 조건:** COUNT에서만 뺐다. R01의 연쇄 삭제 보장이 깨지면 목록과 개수가 어긋날 수 있다(버그로 간주).

## 6. 회고

- **R03 결정이 R04를 싸게 만들었다.** 좋아요 수를 요청마다가 아니라 5초 배치로 반영하게 바꿔 둔 덕분에 `products` 비정규화의 쓰기 경합이 작았고, 잠금 순서만 맞추면 됐다.
- **문답 뒤 계획 단계에서 새 문제가 나왔다.** 전체 개수 캐시가 테스트 사이로 새는 문제는 문답에서 보지 못했다. 코드 구조(공유 싱글톤 빈)까지 내려가야 보이는 문제는 계획 작성 시 다시 확인해야 한다.
- **테스트가 무엇을 증명하는지 확인해야 한다.** 에이전트의 덮어쓰기 테스트는 통과했지만 보호 설정이 없어도 통과하는 구조였다. 보호 장치를 잠시 빼서 테스트가 실패하는지 보는 확인이 유효했다.
- **위임 시간이 길었다(약 25분).** 대량 데이터 적재와 커밋마다의 테스트 실행 때문이었다. 이를 계기로 테스트 기동 비용을 측정했고, `check`의 테스트를 한 JVM으로 합치는 별도 변경(PR #17)으로 이어졌다.

# 상품 목록 조회 개선 전 기준선 (W4)

설계 문서를 쓰기 위한 측정 원자료다. 개선 전 상태는 DB에 보존하지 않고 이 문서에 기록한다. 개선 후에는 같은 절차로 다시 측정해 비교한다.

## 1. 측정 환경과 한계

| 항목 | 값 |
|---|---|
| DB | 로컬 docker MySQL 8.0.46 (`docker/infra-compose.yml`), 스키마 `loopers` |
| 스키마 생성 | `local` 프로필 앱이 `ddl-auto: create`로 생성 (`column_ordering_strategy: legacy` 적용) |
| 데이터 | `src/test/resources/sql/perf-seed.sql` (난수 없음, 같은 입력이면 같은 자료) |
| 지연 측정 | SQL만 순차 실행, 준비 20회·측정 100회, 3회 반복 (`ProductQueryLatencyBenchmarkTest`) |
| 한계 | 단일 로컬 DB, 동시 요청 없음, HTTP 지연은 재지 않음. p95·max는 로컬 환경 잡음이 크다 |

seed는 성능 측정에서만 쓴다. `local` 프로필 앱을 다시 띄우면 `ddl-auto: create`로 지워지므로 필요할 때 다시 적재한다.

## 2. seed 구성과 검산 값

- 브랜드 100개(id 1..100), 사용자 1,000명, 노출 상품 10,000개(브랜드 = `(i % 100) + 1`, 가격 = `((i * 37) % 100000) + 100`, 재고 100), 삭제 상품 200개(id 10001..10200).
- 좋아요 55,808건. 상품 i의 좋아요 수: `i % 11 = 0` → 0개, `i % 97 = 0` → 100~300개(인기), 그 외 → `1 + (i % 7)`개(같은 값이 많이 반복되어 동점). 삭제 상품에도 같은 규칙으로 넣어 집계·정렬에 섞이지 않는지 본다.
- 가격은 `i * 37 mod 100000`이 서로 달라 **가격 동점이 없다**. 동점 규칙은 기존 단위 테스트(`ProductRepositoryTest`, `ProductApiE2ETest`)가 작은 자료로 검증한다.

| 검산 | 값 |
|---|---|
| 노출 상품 수 / 가격 합 | 10,000 / 472,685,000 |
| 브랜드 7 노출 상품 수 / 가격 합 | 100 / 4,647,200 |
| 삭제 상품 수 | 200 |
| 좋아요 / 사용자 / 브랜드 | 55,808 / 1,000 / 100 |

브랜드 7은 상품 100개로, 좋아요 0개 상품 9개, 인기 상품 1개(150개), 동점 구간을 모두 가진다. 좋아요 수 최댓값은 300이며 동점인 상품이 19개다.

## 3. 측정 대상 요청

| 구분 | 요청 (`GET /api/v1/products?`) | 선택 이유 |
|---|---|---|
| **A. 과제 필수** | `brandId=7&sort=price_asc&page=0&size=20` | 과제가 정한 측정 대상(브랜드 필터 + 가격순, 기본 page·size). 브랜드 인덱스 교체가 3주차 잠금에 미치는 영향도 이 요청과 연결된다 |
| **B. 병목 기준선** | `sort=likes_desc&page=0&size=20` (브랜드 없음) | 후보 전체의 좋아요를 집계해 정렬한 뒤 20개를 고르는 가장 비싼 요청. 과제의 "`likes_desc`는 전체 후보에서 정렬한 뒤 페이지를 선택" 비용이 그대로 드러난다 |
| 참고 | `brandId=7&sort=likes_desc`, 브랜드 없는 `price_asc`·count | 비교용 |

요청당 SELECT는 모두 4개다(N+1 없음).

| # | 목적 | 문장 (파라미터는 `?`) |
|---|---|---|
| 1 | 상품 페이지 | `select p1_0.id,p1_0.brand_id,p1_0.created_at,p1_0.deleted_at,p1_0.name,p1_0.price,p1_0.stock,p1_0.updated_at from products p1_0 where p1_0.deleted_at is null and (? is null or p1_0.brand_id=?) order by p1_0.price,p1_0.id desc limit ?` |
| 2 | 총 개수 | `select count(p1_0.id) from products p1_0 where p1_0.deleted_at is null and (? is null or p1_0.brand_id=?)` |
| 3 | 브랜드 | `select b1_0.id,... from brands b1_0 where b1_0.id in (?) and b1_0.deleted_at is null` |
| 4 | 좋아요 수 | `select l1_0.product_id,count(*) from likes l1_0 where l1_0.product_id in (?,...20개) group by l1_0.product_id` |

`likes_desc`의 1번은 `products left join likes ... where ... group by p1_0.id order by count(...) desc, p1_0.id desc limit ?`이고 나머지는 같다. `brandId`가 없으면 파라미터는 `null`이다.

## 4. 실행 계획 (개선 전)

`EXPLAIN ANALYZE`는 계측 오버헤드가 있어 5절의 JDBC 지연보다 크게 나온다. 읽은 행과 접근 경로를 비교하는 용도다.

### A. 브랜드 7 `price_asc`

| # | 접근 | 읽은 행 | 정렬 | 시간 |
|---|---|---|---|---|
| 1 | `idx_products_brand_id` ref(brand_id=7) → `deleted_at` 필터 | 102 → 100 | `Using filesort` (100행 → 20행) | 약 0.12ms |
| 2 | 같은 인덱스 ref → 필터 → 집계 | 102 → 100 | 없음 | 약 0.09ms |
| 3 | PRIMARY const | 1 | 없음 | 무시할 수준 |
| 4 | `idx_likes_product_id` 커버링 range (20개 상품) | 78 | 없음 | 약 0.04ms |

### B. 브랜드 없는 `likes_desc`

| # | 접근 | 읽은 행 | 정렬 | 시간 |
|---|---|---|---|---|
| 1 | `products` PRIMARY 인덱스 스캔 → 필터, 상품마다 `likes` 기본키 접두 조회(커버링, 10,000회) | 상품 10,200 → 10,000, 좋아요 **55,640** | `Using temporary; Using filesort` (group by 후 전체 정렬, top 20) | 약 18~25ms |
| 2 | `products` 전체 스캔(`type=ALL`) → 필터 → 집계 | 10,200 → 10,000 | 없음 | 약 1.7ms |
| 3 | PRIMARY range (브랜드 19개) | 19 | 없음 | 약 0.02ms |
| 4 | `idx_likes_product_id` 커버링 range (인기 상품 20개) | **5,950** | 없음 | 약 0.9ms |

1번에서 `likes` 조회에 쓰는 key는 실행마다 기본키와 `idx_likes_product_id` 사이에서 바뀔 수 있다(둘 다 `product_id` 접두라 같은 일을 한다).

### 참고

| 요청 | 접근 | 읽은 행 | 시간 |
|---|---|---|---|
| 브랜드 없는 `price_asc` 목록 | `products` 전체 스캔 + `Using filesort` | 10,200 → 10,000행 정렬 | 약 2.5~2.8ms |
| 브랜드 없는 count | `products` 전체 스캔 | 10,200 | 약 1.5ms |
| 브랜드 7 `likes_desc` 목록 | 브랜드 인덱스 ref + `likes` 커버링 | 상품 100, 좋아요 약 524 | 약 0.3ms |

원문은 `EXPLAIN`·`EXPLAIN ANALYZE` 출력이며 위 값은 거기서 옮긴 요약이다. 사용 key는 구현 세부이므로 정답으로 고정하지 않는다.

## 5. 지연 (SQL 합계, ms)

4개 문장을 앱과 같은 순서로 실행한 DB 시간이며 HTTP 시간이 아니다. 라운드 1 / 2 / 3.

| 요청 | 합계 p50 | 합계 p95 | 합계 max | 목록 쿼리 p50 |
|---|---|---|---|---|
| A. 브랜드 7 `price_asc` | 1.220 / 1.209 / 1.181 | 2.086 / 2.276 / 1.910 | 2.795 / 3.539 / 2.871 | 0.351 / 0.349 / 0.350 |
| 참고. 브랜드 7 `likes_desc` | 1.024 / 0.947 / 0.945 | 1.186 / 1.071 / 1.089 | 1.394 / 1.126 / 2.625 | 0.393 / 0.357 / 0.353 |
| **B. 브랜드 없는 `likes_desc`** | **14.700 / 15.085 / 14.643** | 16.554 / 15.614 / 15.441 | 22.569 / 17.939 / 15.965 | **12.492 / 12.695 / 12.421** |

- A는 합계의 약 30%가 목록 쿼리이고, 요청 시간은 개별 쿼리의 비용보다 왕복 횟수(4회)에 가깝다. **브랜드 하나가 100개뿐이라 A에서는 개선 여지가 거의 없다.**
- B는 합계의 약 85%가 목록 쿼리이며 A의 약 12배다. 라운드 간 편차가 작아(p50 14.6~15.1) 안정적인 기준선이다.

## 6. 응답 체크섬 (개선 전)

앱 응답의 `data`를 키 정렬·압축 JSON으로 정규화한 뒤 MD5로 비교한다. 시각 등 업무 결과가 아닌 필드는 응답에 없다(`id, name, price, brand, likeCount`와 `page, size, totalElements, totalPages`).

| 요청 (`/api/v1/products?`) | MD5 |
|---|---|
| `brandId=7&sort=price_asc&page=0&size=20` (A) | `4e83a284bbea1bb53364ce1520419cb9` |
| `brandId=7&sort=price_asc&page=4&size=20` | `2cfd7c4d0c847ee77e398ed0a2c7343d` |
| `brandId=7&sort=likes_desc&page=0&size=20` | `e1c02f0f9f3f52dabcfe9d03eba594f1` |
| `brandId=7&sort=likes_desc&page=2&size=20` | `8b1b6337e24aa1f02992fbcc7a4eaebc` |
| `brandId=7&sort=latest&page=0&size=20` | `ab077b2afa82dde6658aadca05026354` |
| `sort=likes_desc&page=0&size=20` (B) | `3593d398ea053bd8d6a2312d8208be4c` |
| `sort=likes_desc&page=2&size=20` | `58cd6dff94bc2b1b61977123ff372151` |
| `sort=likes_desc&page=499&size=20` (마지막 페이지) | `1651eaed56555e7303c5a40f5843dad6` |

앱을 새로 띄워 테이블을 다시 만들고 seed를 다시 적재해도 브랜드 7의 5개 값은 같았다(결과가 결정적이다). B의 첫 페이지는 좋아요 300개 동점 상품이 id 내림차순으로 이어지고(9894, 9409, 8924, …), 마지막 페이지는 좋아요 0개 상품이다.

재현 절차:

```bash
# 1) local 프로필 앱 기동(ddl-auto: create 로 스키마 생성) 후 seed 적재
./gradlew :apps:commerce-api:bootRun --args='--spring.profiles.active=local'
docker exec -i docker-mysql-1 mysql -uapplication -papplication loopers < apps/commerce-api/src/test/resources/sql/perf-seed.sql

# 2) 응답 정규화 후 MD5 (brandId 가 없는 요청은 해당 파라미터를 뺀다)
curl -s 'http://localhost:8080/api/v1/products?sort=likes_desc&page=0&size=20' \
  | jq -S -c '.data' | python3 -c 'import sys,hashlib;print(hashlib.md5(sys.stdin.buffer.read()).hexdigest())'

# 3) 지연(선택)
PERF_JDBC_URL='jdbc:mysql://localhost:3306/loopers' PERF_JDBC_USER=application PERF_JDBC_PASSWORD=application \
  ./gradlew :apps:commerce-api:test --tests '*ProductQueryLatencyBenchmarkTest' -i
```

체크섬이 다르면 정규화한 JSON을 `diff`해 다른 행·필드를 찾는다. 인덱스·쿼리를 바꾼 뒤 이 값이 달라지면 성능 개선으로 세지 않는다.

## 7. 측정 중 확인한 사실

1. **`likes`의 PK는 `(product_id, user_id)`다.** `Like`의 선언은 `(userId, productId)`였지만 Hibernate 기본 컬럼 정렬 전략(`ColumnOrderingStrategyStandard`)이 기본키 컬럼을 물리 크기, 컬럼 이름 순으로 재배열했다. 15개 변형(`@EmbeddedId`·`@IdClass`, record·일반 클래스, 선언 순서)으로 확인했고, 정렬 기준은 속성 이름이 아니라 컬럼 이름이었다. `hibernate.column_ordering_strategy: legacy`로 바꾸면 record 복합키는 선언 순서를 따르고 일반 클래스는 속성 이름 순이다. 그래서 복합키는 record로 선언하고, `LikeRepositoryTest.Schema`가 결과 DDL을 고정한다.
2. 위 사실 때문에 `idx_likes_product_id`(W2에서 PK가 `(user_id, product_id)`라는 잘못된 전제로 추가)는 기본키 접두와 겹친다. 제거 전후 비교는 9절이며, 차이가 없어 코드에서 제거했다.
3. **내 좋아요 목록은 `user_id` 조건에 쓸 인덱스가 없어 `products` 전체 스캔이었다**(`ProductJpaRepository.findAllActiveLikedBy`). 사용자당 좋아요는 46~62건(평균 55.8)인데 비용은 좋아요 수가 아니라 `products` 크기에 비례한다. 같은 seed에서 `EXPLAIN ANALYZE` 3회(사용자 1: 58건, 사용자 94: 62건)로 비교했다.

   | user_id 인덱스 | 목록 쿼리 | count 쿼리 | 접근 |
   |---|---|---|---|
   | 없음 | 약 7ms | 약 6.3ms | `products` 전체 스캔 10,028행 + `likes` PK 조회 10,000회 (두 사용자 시간이 거의 같다) |
   | `(user_id)` | 약 0.16~0.19ms | 약 0.07~0.09ms | `likes`에서 약 60행 조회 + `products` PK 조회 |
   | `(user_id, liked_at)` | 약 0.10~0.12ms | 약 0.07~0.10ms | 위와 같고 `likes` 쪽이 커버링 |

   사용자당 좋아요가 적어 정렬(`Using filesort`)은 어느 구성이든 약 60행이라 무시할 수준이다. 그래서 `(user_id)` 한 컬럼 인덱스(`idx_likes_user_id`)를 두었다. 인덱스가 늘리는 쓰기 비용은 측정하지 않았다. 상품이 10만 건이 되면 인덱스가 없을 때의 비용도 비례해 커질 것으로 보이나 측정하지 않았다.

## 8. 개선 방향에 대한 가설 (측정 전)

- **B의 비용은 `products` 쪽 인덱스로 줄지 않는다.** 시간의 대부분이 `likes` 55,640행을 상품별로 집계하고 전체를 정렬하는 데 쓰인다. 후보 전체의 좋아요 수가 있어야 순서를 정할 수 있기 때문이다.
- 가능한 방향과 한계:
  1. `likes`를 `product_id`별로 먼저 집계한 파생 테이블과 `products`를 join하는 재작성: 읽는 `likes` 행은 같아서 개선 폭은 측정해야 안다.
  2. 좋아요 카운터 비정규화: 이번 범위 밖이다(D-12 재검토 조건, 3주차 동시성 규칙과 함께 판단).
  3. 캐시: 다음 단계의 설계 입력이다. 캐시 없이 B를 줄일 수단이 제한적이라는 것이 이 기준선의 결론이다.
- 이 중 어느 것도 구현하거나 측정하지 않았다.

## 9. `idx_likes_product_id` 제거 전후 비교

같은 seed로 앱이 엔티티에서 스키마를 새로 만든 뒤(제거 후 스키마에는 `idx_likes_product_id`가 없다) 4~6절과 같은 방식으로 다시 측정했다.

| 항목 | 제거 전 | 제거 후 |
|---|---|---|
| 앱 응답 체크섬 8개 | 6절 | **모두 동일** |
| A 합계 p50 (라운드 1/2/3) | 1.220 / 1.209 / 1.181 | 1.216 / 1.162 / 1.194 |
| 참고. 브랜드 7 `likes_desc` 합계 p50 | 1.024 / 0.947 / 0.945 | 0.940 / 1.078 / 0.933 |
| B 합계 p50 | 14.700 / 15.085 / 14.643 | 15.298 / 15.213 / 14.649 |
| B 목록 쿼리 p50 | 12.492 / 12.695 / 12.421 | 12.948 / 12.845 / 12.387 |
| B 4번째 쿼리(좋아요 수) 계획 | `idx_likes_product_id` 커버링 range, 5,950행, 약 0.9ms | 기본키 range(`Using index`), 5,950행, 약 1.0ms |
| B 1번째 쿼리 `EXPLAIN ANALYZE` | 약 18~25ms | 약 18.5~25ms |

- 지연·읽은 행·응답 모두 라운드 간 편차 안에서 같다. 보조 인덱스가 클러스터드 PK보다 행이 좁아(`liked_at` 없음) 좋아요 5만 행을 훑을 때 유리할 수 있다는 가설은 이 규모(좋아요 55,808건)에서는 관찰되지 않았다.
- 제거로 얻는 것은 쓰기(좋아요 등록·취소) 때 유지할 인덱스 하나와 저장 공간이다. 이 둘은 측정하지 않았다.
- 한계: 로컬 단일 DB, 좋아요 5만 건, 읽기만 측정. 좋아요가 수백만 건으로 커지면 좁은 보조 인덱스의 이점이 생길 수 있는지는 확인하지 않았다.

## 10. 개선 후 결과 (구현 완료, 설계: `product-query-design.md`)

같은 seed·같은 방식으로 다시 측정했다. 스키마는 앱이 엔티티에서 새로 만들었고(`idx_products_brand_price_id`, `product_like_counts`), SQL은 앱이 실제로 보내는 문장이다.

| 요청 | 합계 p50 (전 → 후) | 목록 쿼리 p50 (전 → 후) | 비고 |
|---|---|---|---|
| A 브랜드 7 `price_asc` | 1.18~1.22 → 1.15~1.20ms | 0.35 → 0.33~0.35ms | 균등 seed(브랜드 100행)에서는 편차 안 |
| A, 브랜드 7에 5,000개 쏠림 | **3.61~3.69 → 1.46~1.72ms** | 1.74~1.81 → 0.39~0.43ms | `filesort` 제거, count 커버링 |
| **B 브랜드 없는 `likes_desc`** | **14.6~16.3 → 1.81~1.94ms** | 12.4~13.3 → 0.37~0.39ms | SELECT 4 → 5개(id, 상품, count, 브랜드, 좋아요 수) |
| 브랜드 7 `likes_desc` | 0.95~1.02 → 0.73~0.80ms | 0.35~0.39 → 0.26~0.28ms | 좋아요 수 조회가 group by에서 PK 조회로 |
| 브랜드 7에 5,000개 쏠림, `likes_desc` | 4.24~4.85ms | 3.35~3.84ms | 개선 전 값은 측정하지 않음. 브랜드 크기에 비례(한계) |
| 참고 브랜드 없는 `price_asc` | 3.28~3.50ms (변화 없음) | 1.81~1.96ms | 전역 인덱스는 count 계획을 바꿔 채택하지 않음 |

- **응답 체크섬 8개가 개선 전과 모두 같다.** 새 인덱스, 카운터 테이블, 새 쿼리를 모두 거친 결과다.
- B의 실행 계획: 단계 ①은 `idx_product_like_counts_ranking`에서 20행만 읽고(`Using index`), 단계 ②는 PK range 20행이다. 합계에서 가장 큰 비용은 브랜드 없는 count(약 1.15ms)다.
- 카운터 쓰기 비용(후보 측정 때 로컬, 10스레드가 한 상품): 순차 좋아요 TX p50 0.85~1.06ms(집계 없을 때 0.75~0.88), 동시 처리량 1,307~1,445/s(없을 때 3,121~3,959/s). 그 외 환경에서는 측정하지 않았다.
- 한계: 로컬 단일 DB, 좋아요 5.6만 건, 읽기·쓰기를 따로 측정했고 읽기와 쓰기가 섞인 부하는 재지 않았다.

## 11. 아직 확인하지 않은 것

- 깊은 페이지의 지연(체크섬만 있음), 브랜드 없는 `price_asc`의 개선(전역 인덱스는 채택하지 않음).
- HTTP 전체 지연과 동시 요청.

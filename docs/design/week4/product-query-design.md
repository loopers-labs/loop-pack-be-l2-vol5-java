# 상품 조회 SQL·인덱스 개선 설계 (W4)

> 기존 상품 목록 조회의 병목을 **조회 계약을 바꾸지 않고** 줄이고, 방법은 측정으로 고른다. 캐시는 이 문서의 범위 밖이며 이 개선이 끝난 뒤 별도로 설계한다.

근거 자료: 측정 원자료와 재현 절차는 [`product-list-baseline.md`](product-list-baseline.md). 과제 요구는 `docs/note/week4/실습 과제.md` 1·2장, 개념은 `발제.md`.

## 1. 범위

| 구분 | 내용 |
|---|---|
| 포함 | **A** 브랜드 필터 + `price_asc` 목록의 인덱스(과제 필수) / **B** 브랜드 없는 `likes_desc` 목록의 비용 |
| 제외 | 캐시, 커서·`limit` 계약 교체, 옵티마이저 힌트(`STRAIGHT_JOIN` 등) |
| 이미 반영 | `likes` 기본키를 `(product_id, user_id)`로 고정(`legacy` 컬럼 정렬 전략), `idx_likes_product_id` 제거, `idx_likes_user_id` 추가. week2 D-12·D-17·D-18의 서술을 정정함 |

## 2. 조회 계약 (개선 중 바꾸지 않는다)

| 항목 | 계약 |
|---|---|
| 입력 | `brandId`(선택), `sort`=`latest`(기본)\|`price_asc`\|`likes_desc`(소문자 정확 일치), `page`(0 이상, 기본 0), `size`(1~100, 기본 20) |
| 정렬 | `latest`: id 내림차순 / `price_asc`: 가격 오름차순, 동점은 id 내림차순 / `likes_desc`: 좋아요 수 내림차순, 동점은 id 내림차순. **후보 전체를 정렬한 뒤 페이지를 자른다** |
| 필터 | 삭제 상품 제외. 없거나 삭제된 브랜드는 오류가 아니라 빈 목록(200) |
| 응답 | `{id, name, price, brand:{id,name}, likeCount}` + `PageResponse`(`content, page, size, totalElements, totalPages`). 재고·시각은 없음. `totalElements`는 좋아요 행이 아니라 상품 수 |
| 오류 | 잘못된 `sort`·`page`·`size` → `INVALID_INPUT` |

## 3. 기준선

환경: 로컬 docker MySQL 8.0.46, `local` 프로필 앱이 만든 스키마, SQL만 순차 측정(준비 20·측정 100, 3라운드).

- **seed**(`perf-seed.sql`, 난수 없음): 상품 10,000개(브랜드 `(i%100)+1`, 가격 `((i*37)%100000)+100`), 브랜드 100개, 사용자 1,000명, 삭제 상품 200개. 좋아요 55,808건: 상품 i의 좋아요 수는 `i%11=0`이면 0개, `i%97=0`이면 100~300개(인기), 그 외 `1+(i%7)`개(동점 다수). 검산: 전체 10,000건·가격 합 472,685,000, 브랜드 7은 100건·4,647,200. 가격은 서로 달라 동점이 없다(동점 규칙은 기존 단위 테스트가 검증).
- **요청당 SELECT 4개**: ① 상품 페이지 ② 총 개수 ③ 브랜드 `IN` ④ 좋아요 수 `IN … group by`. 목록 단위 일괄 조회라 N+1은 없다.

| 요청 | 합계 p50 | 읽은 양 / 계획 |
|---|---|---|
| **A** `brandId=7&sort=price_asc` | 약 1.2ms (목록 쿼리 0.35ms) | `idx_products_brand_id` ref 102행 → 필터 100행 → `Using filesort` |
| **B** `sort=likes_desc` (브랜드 없음) | 약 14.6~16.3ms (목록 쿼리 12.4~13.3ms) | `products` 10,000행을 돌며 `likes` 10,000회 조회 → 좋아요 55,640행 집계 → `Using temporary; Using filesort` |
| 참고 브랜드 없는 `price_asc` | `EXPLAIN ANALYZE` 약 2.5~2.8ms | `products` 전체 스캔 + 1만 행 `Using filesort` |

- **A는 브랜드 하나가 100행이라 개선 여지가 작다.** 요청 시간은 쿼리 비용보다 SELECT 4회의 왕복에 가깝다. 그래도 과제 필수 대상이므로 인덱스를 설계하고, 상품이 몰린 브랜드(5,000개)로도 비교한다.
- **B가 실제 병목**이다. 비용 대부분이 후보 전체의 좋아요를 집계하고 정렬하는 데 있다.
- **결과 비교**: 응답을 정규화(`jq -S`)해 MD5를 비교하는 체크섬 8개(baseline 6장). 하나라도 다르면 개선으로 세지 않는다.

## 4. 개선 설계

### 4-A. products 인덱스 (결정: C1)

쿼리 모양: `where deleted_at is null and brand_id = ? order by price, id desc limit 20`, count는 같은 `where`. `brand_id`는 동등이라 맨 앞, `deleted_at is null`은 상수라 그 뒤에 두어도 `price, id` 순서가 유지된다. 정렬이 `price asc, id desc`로 섞여 `id DESC`(MySQL 8 내림차순 인덱스)를 명시해야 인덱스만으로 정렬이 끝난다.

| 후보 | 균등 seed(브랜드 100행) | 쏠림(브랜드 7에 5,000행) | 판정 |
|---|---|---|---|
| C0 현행 `(brand_id)` | 목록 0.11ms, 요청 p50 1.14~1.25ms | 목록 1.7~1.8ms, **요청 3.6~3.7ms** | 기준 |
| **C1** `(brand_id, deleted_at, price, id DESC)` | 목록 0.03ms(20행, filesort 제거), count 커버링. 요청 1.12~1.15ms(편차 안) | 목록 0.39~0.42ms, **요청 1.5~1.6ms** | **채택** |
| C2 `(brand_id, price, id DESC)` | 목록 0.03ms, count는 행을 읽음 | count 1.7~1.9ms로 요청 2.3~3.1ms | 탈락 |
| C3 `(brand_id, price)` | `filesort` 남음(0.09ms) | - | 탈락 |
| C4 전역 `(deleted_at, price, id DESC)` | 브랜드 없는 목록 1.6~1.8 → 0.28ms | **같은 인덱스가 브랜드 없는 count 계획을 바꿔 1.7 → 3.2ms**, 요청 합계 3.0~3.3 → 3.0~3.2ms | 탈락 |

- 균등 seed에서는 C1의 이득이 라운드 편차 안이다. **상품이 몰린 브랜드에서 크게 차이 나고**, 기존 인덱스를 교체하므로 인덱스 수는 늘지 않는다. 정렬된 id 해시는 모든 후보가 같다.
- 비용: 인덱스가 넓어져 가격 수정·삭제 때 갱신이 늘지만 관리자 쓰기라 드물다(쓰기 비용은 측정하지 않았다).
- **게이트 결과**: 교체하면 브랜드 삭제 `FOR UPDATE`의 갭 잠금이 닿는 이웃이 **직전 브랜드에서 다음 브랜드로 뒤바뀐다**(새 행이 같은 브랜드·가격 행들 맨 앞에 놓이기 때문). 대기하는 이웃이 한쪽이고 삭제 한 건 동안이라는 영향의 크기는 같고, 같은 브랜드의 수정·등록은 여전히 막힌다. `LockBehaviorCharacterizationTest`의 기대값과 week3 문서를 이에 맞췄다.

### 4-B. `likes_desc` (브랜드 없음, 결정: 좋아요 카운터 테이블)

| 후보 | 목록 쿼리 p50 | 요청 합계 p50 | 판정 |
|---|---|---|---|
| B0 현행 `left join likes group by` | 12.9~13.3ms | 15.9~16.3ms | 기준 |
| B1 파생 테이블 선집계 | 10.8~10.9ms | 13.6~13.9ms | 약 15~17%만 개선. 좋아요 5만 행 집계가 바닥 |
| B4a `products.like_count` 컬럼 | 0.42~0.44ms | 1.6ms | 읽기는 좋으나 **좋아요가 `products` 행을 갱신·잠금**해 주문·브랜드 삭제·관리자 수정과 경쟁, 동시 좋아요 처리량 -65~70% |
| B5 카운터 테이블 + 조인 | 5.0~5.4ms | 6.6~7.1ms | 옵티마이저가 `products`부터 읽음 |
| B5s 위 + `STRAIGHT_JOIN` | 0.34~0.35ms | 1.84~1.86ms | 조인 순서 고정은 분포가 바뀌면 오히려 해롭고 이해·유지가 어려워 제외 |
| 안티 조인(`NOT EXISTS`) | - | - | 결과는 같으나 삭제 상품 집합을 materialize해 **상품 수에 비례**(1만 행에서 약 1ms) |
| **B6 카운터 테이블에서 id 선택 → 상품 조회** | ①+② 약 0.57ms | **2.02~2.09ms** | **채택** |

**B6 구조**: 별도 테이블 `product_like_counts(product_id PK, like_count)`, 인덱스 `(like_count DESC, product_id DESC)`. 행은 **노출 중인 상품만** 가진다.
① 카운터 인덱스에서 페이지의 `product_id`를 읽는다(단일 테이블 index-only, 20행, 0.24~0.26ms). ② 그 id로 `products`를 PK 조회하고 `deleted_at is null`을 한 번 더 확인한다(0.32~0.33ms). 합계의 나머지는 기존 count(약 1.15ms)와 브랜드 조회다. 정렬된 id·좋아요 수 해시는 B0와 같다(페이지 0·40·9980).

- **단일 출처**: 모든 목록·상세의 `likeCount`와 모든 `likes_desc`가 이 테이블을 읽는다. 정렬 키와 표시 값이 같고, 좋아요 수 조회는 `group by`에서 PK 조회로 바뀐다. 브랜드 필터 `likes_desc`는 `products`와 카운터를 `left join`(`coalesce`)한다. 브랜드 범위가 좁아 옵티마이저 순서가 안정적이다.
- **쓰기 책임(D-12 변경)**: 좋아요 등록·취소 TX가 카운터 행만 원자적으로 ±1 한다(실제로 행이 생기거나 지워졌을 때만, `like_count >= 0`). `products`는 건드리지 않는다. 측정: 순차 TX p50 0.85~1.06ms(없을 때 0.75~0.88), 같은 상품 동시 좋아요 1,307~1,445/s(없을 때 3,121~3,959/s).
- **행의 생명주기**: 상품 저장(생성) 때 행을 만들고, 삭제 처리 때 지운다. 상품 삭제·브랜드 일괄 삭제 경로가 `ProductService`의 삭제 메서드를 거치게 한다. 좋아요 경로는 삭제와 경쟁하지 않는다(좋아요 TX는 상품 행을 잠그지 않는다).
- **위험**: 행이 빠지면 그 상품이 브랜드 없는 `likes_desc`에서 빠진다(정합성 테스트로 고정). 기존 데이터는 backfill SQL로 맞춘다. 브랜드 필터가 없는 count(약 1.15ms)는 그대로라 이 요청의 하한이다.

### 4-C. 유지·조정하는 것

- 요청당 SELECT는 4개를 유지한다. 단 브랜드 없는 `likes_desc`는 id 선택이 추가되어 5개(id, 상품, count, 브랜드, 좋아요 수)다.
- 내 좋아요 목록은 `idx_likes_user_id`로 약 7ms → 0.16ms가 됐다(반영 완료).

## 5. 검증

- 응답 체크섬 8개가 개선 전과 모두 같다. 다르면 JSON을 `diff`해 다른 행·필드를 찾는다.
- 기존 `ProductRepositoryTest`·`ProductFacadeTest`·`ProductApiE2ETest`(정렬·동점·삭제·브랜드·count·페이지), 스키마 테스트(인덱스 컬럼·방향), 카운터 정합성·동시성 테스트, 잠금 특성 테스트, `./gradlew :apps:commerce-api:check`.
- 전후 `EXPLAIN ANALYZE`(읽은 행·loops·정렬 단계)와 JDBC 지연 3라운드. 차이가 라운드 편차 안이면 채택하지 않는다(균등 seed의 C1은 쏠림 비교로 보강).

## 6. 결정 기록

| 번호 | 결정 | 상태 |
|---|---|---|
| D-30 | 조회 계약과 OFFSET 페이지를 유지한다 | 확정 |
| D-31 | A는 `(brand_id, deleted_at, price, id DESC)`로 `idx_products_brand_id`를 교체한다. 갭 잠금 이웃이 직전→다음 브랜드로 바뀐다 | 확정 |
| D-32 | 전역 가격순 인덱스(C4)는 채택하지 않는다 | 확정 |
| D-33 | B는 카운터 테이블(B6)로 개선하고 `likeCount`의 단일 출처로 삼는다. **D-12(카운터 없음)를 대체한다** | 확정 |
| D-34 | 옵티마이저 힌트(`STRAIGHT_JOIN`)는 쓰지 않는다. 요청당 SELECT는 4개이며 브랜드 없는 `likes_desc`만 5개다 | 확정(테스트로 고정) |
| D-35 | `likes` 인덱스 정리(기본키 `(product_id, user_id)`, `idx_likes_product_id` 제거, `idx_likes_user_id` 추가)는 반영했다 | 확정 |
| D-36 | 상품을 삭제해도 좋아요 관계는 지우지 않고, 삭제 상품은 읽기에서 무시한다. 같은 TX에서 지우면 관리자 삭제 TX가 커지고 likes 범위 잠금이 생기는데, 상품을 읽은 뒤 삽입하는 좋아요 TX와의 경쟁은 막지 못해 읽기 필터를 없앨 수 없다. 복원 시 좋아요도 되살릴 수 없게 된다 | 확정 |

## 7. 구현 결과

| 요청 | 합계 p50 (전 → 후) | 비고 |
|---|---|---|
| A 브랜드 7 `price_asc` | 1.18~1.22 → 1.15~1.20ms | 균등 seed는 편차 안 |
| A, 브랜드 7에 5,000개 쏠림 | 3.61~3.69 → **1.46~1.72ms** | `filesort` 제거, count 커버링 |
| **B 브랜드 없는 `likes_desc`** | 14.6~16.3 → **1.81~1.94ms** | SELECT 5개 |
| 브랜드 7 `likes_desc` | 0.95~1.02 → 0.73~0.80ms | 좋아요 수가 PK 조회로 |

- 응답 체크섬 8개가 개선 전과 모두 같다. 전체 `check`(Checkstyle·ArchUnit·796건)가 통과한다.
- 구현: `ProductLikeCount` 엔티티와 `ProductLikeCountJpaRepository`. 좋아요 등록·취소 때의 ±1은 `LikeService`가 `ProductLikeCountRepository`로 하고(관계를 실제로 만들거나 지웠을 때만), 행은 상품을 만드는 Facade(`AdminProductFacade.create`)가 `LikeService.createCount`으로 0으로 만들고, 상품을 지우는 Facade 2곳(상품·브랜드 삭제)이 `LikeService.removeCounts`으로 지운다. 상품 생성이 이 Facade를 우회하지 않도록 `ProductRepository.save`는 `ProductService`만 호출한다(ArchUnit). 증감은 `UPDATE`만 쓰고 upsert하지 않아, 삭제와 좋아요가 경쟁해도 삭제된 상품의 행이 되살아나지 않는다.
- 검증: 집계 행 수 = 노출 상품 수, 행 값 = 좋아요 관계 수, 동시 등록·취소·중복 등록·중복 취소 후 집계 = 관계 수(20회 반복), 요청당 SQL 수(4개, 브랜드 없는 `likes_desc`만 5개), 삭제 상품이 위에 있어도 페이지가 크기를 채움, 삭제 경로 2곳의 행 정리.
- 운영 반영: `sql/index-products.sql`, `sql/product-like-counts.sql`(테이블·인덱스·backfill·정합성 확인 쿼리). 앱 배포 전에 backfill과 확인 쿼리를 먼저 적용한다.
- 알려진 한계: 브랜드를 지정한 `likes_desc`는 브랜드 크기에 비례한다(5,000개 쏠림에서 합계 약 4.2~4.9ms). 상품이 집계 테이블에 행 없이 만들어지면(저장소를 거치지 않는 직접 INSERT) 브랜드 없는 `likes_desc`에서 빠지므로 backfill이 필요하다. ArchUnit은 저장소 직접 호출만 막고, 새 Facade가 `ProductService.create` 뒤에 `createCount`을 빠뜨리는 것은 막지 못한다(테스트로 잡는다).

## 8. 미측정과 다음 단계

- 미측정: 깊은 페이지, HTTP 전체 지연·동시 요청, 읽기와 쓰기가 섞인 부하, 좋아요가 수백만 건일 때의 거동, 쏠린 브랜드의 `likes_desc` 개선 전 값.
- 다음 단계: 캐시(키·TTL·무효화·장애 계약)는 이 개선 결과를 입력으로 별도 문서에서 설계한다.

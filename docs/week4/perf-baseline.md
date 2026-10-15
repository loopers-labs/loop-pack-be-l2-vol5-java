# 조회 성능 측정 기록

## 측정 환경

- DB: MySQL 8.0 (Docker, 로컬)
- 데이터: 상품 10,000개 / 브랜드 100개 / 유저 200명 / 좋아요 12,000개
- 시드 공식: `brand_id = (i % 100) + 1` / `price = ((i * 37) % 100000) + 100`
- 대상 쿼리: `GET /api/v1/products?brandId=7&sort=price_asc&page=0&size=20`

## 기준선 (인덱스 없음)

```sql
EXPLAIN ANALYZE
SELECT p.id, p.name, p.price, p.stock, p.brand_id
FROM products p
WHERE p.brand_id = 7 AND p.deleted_at IS NULL
ORDER BY p.price ASC, p.id DESC
LIMIT 20;
```

```
-> Limit: 20 row(s)  (cost=1012 rows=20) (actual time=6.85..6.86 rows=20 loops=1)
    -> Sort: p.price, p.id DESC  (cost=1012 rows=9881) (actual time=6.84..6.84 rows=20 loops=1)
        -> Filter: ((p.brand_id = 7) and (p.deleted_at is null))  (cost=1012 rows=9881) (actual time=0.97..6.62 rows=100 loops=1)
            -> Table scan on p  (cost=1012 rows=9881) (actual time=0.924..5.66 rows=10000 loops=1)
```

| 단계 | 읽은 행 | 소요 시간 |
|---|---|---|
| Table scan | 10,000행 | 5.66ms |
| Filter (brand=7, 미삭제) | → 100행 | 6.62ms |
| Sort (price, id DESC) | 100행 정렬 | 6.84ms |
| Limit 20 | 20행 반환 | **6.86ms** |

문제: 테이블 전체(10,000행)를 읽고 9,900행을 버림. 정렬도 메모리에서 별도 수행.

## 인덱스 추가

```sql
CREATE INDEX idx_products_brand_deleted_price ON products (brand_id, deleted_at, price);
CREATE INDEX idx_products_brand_deleted_id    ON products (brand_id, deleted_at, id);
```

## 인덱스 추가 후

```
-> Limit: 20 row(s)  (cost=35 rows=20) (actual time=0.32..0.322 rows=20 loops=1)
    -> Sort: p.price, p.id DESC  (cost=35 rows=100) (actual time=0.319..0.32 rows=20 loops=1)
        -> Index lookup on p using idx_products_brand_deleted_price (brand_id=7, deleted_at=NULL)  (cost=35 rows=100) (actual time=0.0803..0.254 rows=100 loops=1)
```

| 단계 | 읽은 행 | 소요 시간 |
|---|---|---|
| Index lookup (brand=7, deleted=NULL) | 100행 | 0.254ms |
| Sort (price, id DESC) | 100행 정렬 | 0.320ms |
| Limit 20 | 20행 반환 | **0.322ms** |

## 전후 비교

| 항목 | 기준선 | 인덱스 후 |
|---|---|---|
| 접근 방식 | Table scan 10,000행 | Index lookup 100행 |
| 총 실행 시간 | 6.86ms | 0.32ms |
| 개선율 | — | 약 21배 |

## 정렬 단계가 남아있는 이유

인덱스가 `(brand_id, deleted_at, price)` 순서라 `brand_id=7 AND deleted_at IS NULL`까지는 인덱스로 커버된다.
그러나 보조 정렬 `id DESC`가 인덱스 순서에 없어서 정렬 단계를 완전히 제거하지는 못한다.
100행 정렬이라 비용이 미미하므로 현재 구조를 유지한다.

---

## likes_desc 정렬 측정

### 쿼리 구조

```sql
SELECT p.id, ...
FROM products p
LEFT JOIN likes l ON l.product_id = p.id
WHERE p.deleted_at IS NULL
  AND p.brand_id = :brandId        -- 브랜드 필터 (선택)
GROUP BY p.id
ORDER BY COUNT(l.id) DESC, p.id DESC
LIMIT 20;
```

### EXPLAIN ANALYZE — 브랜드 필터 있음 (brandId=7)

```
-> Limit: 20 row(s)  (actual time=10..10 rows=20 loops=1)
    -> Sort: `count(l.id)` DESC, p.id DESC  (actual time=10..10 rows=20 loops=1)
        -> Table scan on <temporary>  (actual time=9.98..9.99 rows=100 loops=1)
            -> Aggregate using temporary table  (actual time=9.98..9.98 rows=100 loops=1)
                -> Left hash join (l.product_id = p.id)  (cost=116200 rows=1.16e+6) (actual time=8.89..9.77 rows=100 loops=1)
                    -> Index lookup on p using idx_products_brand_deleted_price (brand_id=7, deleted_at=NULL)  (cost=35 rows=100) (actual time=0.258..0.98 rows=100 loops=1)
                    -> Hash
                        -> Covering index scan on l using uk_likes_user_product  (cost=11.9 rows=11616) (actual time=1.81..3.73 rows=12000 loops=1)
```

| 단계 | 행 수 | 시간 |
|---|---|---|
| Index lookup (brand=7) | 100행 | 0.98ms |
| Hash build (전체 likes) | **12,000행** | 3.73ms |
| Aggregate (임시 테이블) | 100행 | 9.98ms |
| Sort + Limit | 20행 | **10ms** |

### EXPLAIN ANALYZE — 브랜드 필터 없음 (전체)

```
-> Limit: 20 row(s)  (actual time=24.3..24.3 rows=20 loops=1)
    -> Sort: `count(l.id)` DESC, p.id DESC  (actual time=24.3..24.3 rows=20 loops=1)
        -> Table scan on <temporary>  (actual time=22.1..23.2 rows=10000 loops=1)
            -> Aggregate using temporary table  (actual time=22.1..22.1 rows=10000 loops=1)
                -> Left hash join (l.product_id = p.id)  (cost=1.15e+6 rows=11.5e+6) (actual time=3.28..10.4 rows=21000 loops=1)
                    -> Table scan on p  (cost=1012 rows=9881) (actual time=0.0899..2.9 rows=10000 loops=1)
                    -> Hash
                        -> Covering index scan on l using uk_likes_user_product  (cost=1.24 rows=11616) (actual time=0.115..1.52 rows=12000 loops=1)
```

| 단계 | 행 수 | 시간 |
|---|---|---|
| Table scan (전체 products) | 10,000행 | 2.9ms |
| Hash build (전체 likes) | 12,000행 | 1.52ms |
| Aggregate (임시 테이블) | 10,000행 | 22.1ms |
| Sort + Limit | 20행 | **24.3ms** |

### likes_desc 정렬의 구조적 한계

COUNT 집계값은 행마다 동적으로 계산되므로 인덱스로 정렬 단계를 제거할 수 없다.
페이지를 선택하기 전에 **전체 후보**를 집계·정렬해야 한다.

브랜드 필터가 있으면 집계 대상이 100행으로 줄어 10ms이지만,
상품·좋아요 수가 늘수록 Hash 빌드와 임시 테이블 비용이 함께 증가한다.

근본 개선 방법: `products.like_count` 컬럼으로 역정규화하면 인덱스로 정렬 가능.
현재 단계에서는 범위 밖이므로 기록만 남긴다.

---

## 코드 반영 (미완료)

`ProductModel.java`의 `@Table`에 아래 어노테이션을 추가하면 DDL에 인덱스가 포함된다.

```java
@Table(name = "products", indexes = {
    @Index(name = "idx_products_brand_deleted_price",
           columnList = "brand_id, deleted_at, price"),
    @Index(name = "idx_products_brand_deleted_id",
           columnList = "brand_id, deleted_at, id")
})
```

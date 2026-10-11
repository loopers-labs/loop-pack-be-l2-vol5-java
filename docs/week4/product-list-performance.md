# 상품 목록 조회 성능 — 인덱스 적용 전후 비교

`GET /api/v1/products` (FR-PRODUCT-01, EP-02) 를 대량 데이터에서 측정하고, 인덱스 적용 전후를 케이스별로 비교한다.

## 1. 측정 환경

| 항목 | 값 |
|---|---|
| DB | MySQL 8.0 (docker `docker-mysql-1`), `innodb_buffer_pool_size` 1GB |
| 데이터 | `docs/week4/seed.sql` 로 생성. 브랜드 1,000 / 유저 1,000,000 / 상품 5,000,000 (삭제 249,470) / 좋아요 7,604,867 |
| 테스트 브랜드 | `brandId=1`. 삭제되지 않은 상품 474,993개, 좋아요 1,000개 이상 상품 25개, 최대 좋아요 16,839 (가장 상품이 많고 인기 상품도 많은 브랜드) |
| 페이지 크기 | `size=50` 고정 |
| 딥 페이지 | `page=99` (OFFSET 4,950. `page` 상한 100, DR-30) |
| 인덱스 적용 전 | PK 와 `product_like` 의 `ux_product_like_user_product (user_id, product_id)` 만 있음 |
| 인덱스 적용 후 | 위에 더해 `product`: `ix_product_latest (deleted_at, created_at, id)`, `ix_product_price (deleted_at, price, id)`, `ix_product_brand_latest (brand_id, deleted_at, created_at, id)`, `ix_product_brand_price (brand_id, deleted_at, price, id)` / `product_like`: `ix_product_like_product (product_id)`. 쿼리는 그대로 |

## 2. 측정 방법

- `docs/week4/bench.py` 로 측정한다. SQL 은 이 문서의 케이스별 `sql` 블록을 그대로 읽어 쓴다.
- 케이스마다 `EXPLAIN ANALYZE` 를 워밍업 1회(버림) + 측정 5회 실행한다. 케이스는 L01 → L12 → C01 → C02 순으로 하나씩, 병렬 없이 돌린다.
- 실행 시간은 `EXPLAIN ANALYZE` 맨 위 노드의 `actual time=시작..끝` 중 끝 값(ms)이다. 서버 안에서 잰 값이라 `docker exec`·클라이언트 비용이 섞이지 않는다. 행마다 시간을 재는 계측 비용이 붙어 실제 실행보다 조금 크게 나오지만, 전후 비교는 같은 방식이라 공정하다.
- 표에는 측정 5회의 평균을 적고, 최소~최대를 괄호에 함께 적는다. 실행 계획은 5번째 측정의 결과를 붙인다.
- 원본 결과는 `docs/week4/results/<단계>/` 에 남는다 (`summary.tsv`, `<ID>.plan.txt`).
- SQL 은 앱(`local` 프로필)을 띄워 각 URL 을 호출하고 MySQL general log 로 잡은 실제 SQL 이다. 실제로는 한 줄이고, 읽기 쉽게 줄만 바꿨다.
- 요청 하나는 요청자 조회(`user` PK 조회) → 목록 쿼리 → count 쿼리 순으로 나간다. API 응답 시간은 목록 쿼리 + count 쿼리 시간이 대부분이다.
- 캐시 적용 효과(API 응답 시간)는 이번 측정 범위 밖이다.

## 3. 케이스 분류

목록 쿼리 12개 = 브랜드(전체 / 1번) × 페이지(첫 / 딥) × 정렬(3). count 쿼리는 정렬·페이지와 무관하고 브랜드 조건에만 따라 달라져 2개.

| ID | 브랜드 | 페이지 | 정렬 | URL | count 쿼리 |
|---|---|---|---|---|---|
| [L01](#l01) | 전체 | 첫 페이지 (`page=0`) | `latest` | `/api/v1/products?sort=latest&page=0&size=50` | C01 |
| [L02](#l02) | 전체 | 첫 페이지 (`page=0`) | `price_asc` | `/api/v1/products?sort=price_asc&page=0&size=50` | C01 |
| [L03](#l03) | 전체 | 첫 페이지 (`page=0`) | `likes_desc` | `/api/v1/products?sort=likes_desc&page=0&size=50` | C01 |
| [L04](#l04) | 전체 | 딥 페이지 (`page=99`) | `latest` | `/api/v1/products?sort=latest&page=99&size=50` | C01 |
| [L05](#l05) | 전체 | 딥 페이지 (`page=99`) | `price_asc` | `/api/v1/products?sort=price_asc&page=99&size=50` | C01 |
| [L06](#l06) | 전체 | 딥 페이지 (`page=99`) | `likes_desc` | `/api/v1/products?sort=likes_desc&page=99&size=50` | C01 |
| [L07](#l07) | 1번 브랜드 | 첫 페이지 (`page=0`) | `latest` | `/api/v1/products?sort=latest&page=0&size=50&brandId=1` | C02 |
| [L08](#l08) | 1번 브랜드 | 첫 페이지 (`page=0`) | `price_asc` | `/api/v1/products?sort=price_asc&page=0&size=50&brandId=1` | C02 |
| [L09](#l09) | 1번 브랜드 | 첫 페이지 (`page=0`) | `likes_desc` | `/api/v1/products?sort=likes_desc&page=0&size=50&brandId=1` | C02 |
| [L10](#l10) | 1번 브랜드 | 딥 페이지 (`page=99`) | `latest` | `/api/v1/products?sort=latest&page=99&size=50&brandId=1` | C02 |
| [L11](#l11) | 1번 브랜드 | 딥 페이지 (`page=99`) | `price_asc` | `/api/v1/products?sort=price_asc&page=99&size=50&brandId=1` | C02 |
| [L12](#l12) | 1번 브랜드 | 딥 페이지 (`page=99`) | `likes_desc` | `/api/v1/products?sort=likes_desc&page=99&size=50&brandId=1` | C02 |

## 4. 결과 요약

### 4-1. 목록 쿼리 실행 시간 (평균, ms)

| ID | 브랜드 | 페이지 | 정렬 | 인덱스 적용 전 | 인덱스 적용 후 |
|---|---|---|---|---|---|
| L01 | 전체 | 첫 페이지 | `latest` | 41,410.4 (40,286.0~43,414.0) | 65,350.6 (62,870.0~70,511.0) |
| L02 | 전체 | 첫 페이지 | `price_asc` | 37,686.8 (37,358.0~38,224.0) | 60,067.0 (59,465.0~60,420.0) |
| L03 | 전체 | 첫 페이지 | `likes_desc` | 37,608.6 (36,984.0~37,855.0) | 60,342.2 (59,502.0~61,038.0) |
| L04 | 전체 | 딥 페이지 | `latest` | 41,904.6 (41,552.0~42,176.0) | 66,412.8 (65,336.0~67,741.0) |
| L05 | 전체 | 딥 페이지 | `price_asc` | 38,652.4 (38,128.0~39,168.0) | 60,587.4 (60,004.0~61,452.0) |
| L06 | 전체 | 딥 페이지 | `likes_desc` | 38,378.8 (37,783.0~38,842.0) | 60,413.8 (59,842.0~61,197.0) |
| L07 | 1번 브랜드 | 첫 페이지 | `latest` | 5,605.4 (5,416.0~5,943.0) | 3,216.2 (3,129.0~3,477.0) |
| L08 | 1번 브랜드 | 첫 페이지 | `price_asc` | 5,222.6 (5,088.0~5,281.0) | 2,960.6 (2,915.0~3,102.0) |
| L09 | 1번 브랜드 | 첫 페이지 | `likes_desc` | 5,144.8 (5,063.0~5,229.0) | 2,953.0 (2,910.0~3,059.0) |
| L10 | 1번 브랜드 | 딥 페이지 | `latest` | 5,595.2 (5,394.0~5,720.0) | 3,165.2 (3,117.0~3,267.0) |
| L11 | 1번 브랜드 | 딥 페이지 | `price_asc` | 5,305.6 (5,231.0~5,440.0) | 2,975.0 (2,965.0~3,007.0) |
| L12 | 1번 브랜드 | 딥 페이지 | `likes_desc` | 5,236.6 (5,163.0~5,423.0) | 2,982.0 (2,936.0~3,093.0) |

### 4-2. count 쿼리 실행 시간 (평균, ms)

| ID | 브랜드 | 인덱스 적용 전 | 인덱스 적용 후 |
|---|---|---|---|
| C01 | 전체 | 968.6 (956.0~998.0) | 858.8 (854.0~867.0) |
| C02 | 1번 브랜드 | 896.6 (876.0~963.0) | 98.1 (97.1~99.5) |

## 5. 목록 쿼리 케이스별 상세

### L01

전체 · 첫 페이지 (`page=0`) · `latest` — `/api/v1/products?sort=latest&page=0&size=50`

```sql
select pm1_0.id,pm1_0.name,pm1_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from product pm1_0
join brand bm1_0 on bm1_0.id=pm1_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm1_0.id
where pm1_0.deleted_at is null
group by pm1_0.id,bm1_0.id
order by pm1_0.created_at desc,pm1_0.id desc
limit 0,50
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 41,410.4 (40,286.0~43,414.0) |
| 인덱스 적용 후 | 65,350.6 (62,870.0~70,511.0) |

**실행 계획 — 인덱스 적용 전**

```text
-> Limit: 50 row(s)  (actual time=43414..43414 rows=50 loops=1)
    -> Sort: pm1_0.created_at DESC, pm1_0.id DESC, limit input to 50 row(s) per chunk  (actual time=43414..43414 rows=50 loops=1)
        -> Table scan on <temporary>  (actual time=41727..42785 rows=4.75e+6 loops=1)
            -> Aggregate using temporary table  (actual time=41727..41727 rows=4.75e+6 loops=1)
                -> Left hash join (plm1_0.product_id = pm1_0.id)  (cost=359e+9 rows=3.59e+12) (actual time=1488..13273 rows=10.3e+6 loops=1)
                    -> Nested loop inner join  (cost=973351 rows=485830) (actual time=0.215..4034 rows=4.75e+6 loops=1)
                        -> Filter: (pm1_0.deleted_at is null)  (cost=511813 rows=485830) (actual time=0.161..1587 rows=4.75e+6 loops=1)
                            -> Table scan on pm1_0  (cost=511813 rows=4.86e+6) (actual time=0.159..1364 rows=5e+6 loops=1)
                        -> Single-row index lookup on bm1_0 using PRIMARY (id=pm1_0.brand_id)  (cost=0.85 rows=1) (actual time=384e-6..405e-6 rows=1 loops=4.75e+6)
                    -> Hash
                        -> Covering index scan on plm1_0 using ux_product_like_user_product  (cost=145 rows=7.38e+6) (actual time=0.287..887 rows=7.6e+6 loops=1)
```

**실행 계획 — 인덱스 적용 후**

```text
-> Limit: 50 row(s)  (actual time=62870..62870 rows=50 loops=1)
    -> Sort: pm1_0.created_at DESC, pm1_0.id DESC, limit input to 50 row(s) per chunk  (actual time=62870..62870 rows=50 loops=1)
        -> Table scan on <temporary>  (actual time=61097..62247 rows=4.75e+6 loops=1)
            -> Aggregate using temporary table  (actual time=61097..61097 rows=4.75e+6 loops=1)
                -> Nested loop left join  (cost=865491 rows=1.76e+6) (actual time=1.04..26282 rows=10.3e+6 loops=1)
                    -> Nested loop inner join  (cost=283799 rows=403284) (actual time=1.03..17052 rows=4.75e+6 loops=1)
                        -> Table scan on bm1_0  (cost=105 rows=1000) (actual time=0.0526..1.57 rows=1000 loops=1)
                        -> Index lookup on pm1_0 using ix_product_brand_latest (brand_id=bm1_0.id, deleted_at=NULL), with index condition: (pm1_0.deleted_at is null)  (cost=243 rows=403) (actual time=0.153..16.8 rows=4751 loops=1000)
                    -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pm1_0.id)  (cost=1.01 rows=4.36) (actual time=0.00152..0.00176 rows=1.53 loops=4.75e+6)
```

### L02

전체 · 첫 페이지 (`page=0`) · `price_asc` — `/api/v1/products?sort=price_asc&page=0&size=50`

```sql
select pm1_0.id,pm1_0.name,pm1_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from product pm1_0
join brand bm1_0 on bm1_0.id=pm1_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm1_0.id
where pm1_0.deleted_at is null
group by pm1_0.id,bm1_0.id
order by pm1_0.price,pm1_0.id desc
limit 0,50
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 37,686.8 (37,358.0~38,224.0) |
| 인덱스 적용 후 | 60,067.0 (59,465.0~60,420.0) |

**실행 계획 — 인덱스 적용 전**

```text
-> Limit: 50 row(s)  (actual time=37442..37442 rows=50 loops=1)
    -> Sort: pm1_0.price, pm1_0.id DESC, limit input to 50 row(s) per chunk  (actual time=37442..37442 rows=50 loops=1)
        -> Table scan on <temporary>  (actual time=36121..37007 rows=4.75e+6 loops=1)
            -> Aggregate using temporary table  (actual time=36121..36121 rows=4.75e+6 loops=1)
                -> Left hash join (plm1_0.product_id = pm1_0.id)  (cost=359e+9 rows=3.59e+12) (actual time=1453..11748 rows=10.3e+6 loops=1)
                    -> Nested loop inner join  (cost=972878 rows=485830) (actual time=0.132..3786 rows=4.75e+6 loops=1)
                        -> Filter: (pm1_0.deleted_at is null)  (cost=511340 rows=485830) (actual time=0.103..1364 rows=4.75e+6 loops=1)
                            -> Table scan on pm1_0  (cost=511340 rows=4.86e+6) (actual time=0.101..1144 rows=5e+6 loops=1)
                        -> Single-row index lookup on bm1_0 using PRIMARY (id=pm1_0.brand_id)  (cost=0.85 rows=1) (actual time=379e-6..400e-6 rows=1 loops=4.75e+6)
                    -> Hash
                        -> Covering index scan on plm1_0 using ux_product_like_user_product  (cost=144 rows=7.38e+6) (actual time=0.292..881 rows=7.6e+6 loops=1)
```

**실행 계획 — 인덱스 적용 후**

```text
-> Limit: 50 row(s)  (actual time=60010..60010 rows=50 loops=1)
    -> Sort: pm1_0.price, pm1_0.id DESC, limit input to 50 row(s) per chunk  (actual time=60010..60010 rows=50 loops=1)
        -> Table scan on <temporary>  (actual time=58459..59580 rows=4.75e+6 loops=1)
            -> Aggregate using temporary table  (actual time=58459..58459 rows=4.75e+6 loops=1)
                -> Nested loop left join  (cost=861975 rows=1.76e+6) (actual time=0.356..26201 rows=10.3e+6 loops=1)
                    -> Nested loop inner join  (cost=280283 rows=403284) (actual time=0.349..17028 rows=4.75e+6 loops=1)
                        -> Table scan on bm1_0  (cost=104 rows=1000) (actual time=0.0663..2.04 rows=1000 loops=1)
                        -> Index lookup on pm1_0 using ix_product_brand_latest (brand_id=bm1_0.id, deleted_at=NULL), with index condition: (pm1_0.deleted_at is null)  (cost=240 rows=403) (actual time=0.156..16.8 rows=4751 loops=1000)
                    -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pm1_0.id)  (cost=1.01 rows=4.36) (actual time=0.00151..0.00175 rows=1.53 loops=4.75e+6)
```

### L03

전체 · 첫 페이지 (`page=0`) · `likes_desc` — `/api/v1/products?sort=likes_desc&page=0&size=50`

```sql
select pm1_0.id,pm1_0.name,pm1_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from product pm1_0
join brand bm1_0 on bm1_0.id=pm1_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm1_0.id
where pm1_0.deleted_at is null
group by pm1_0.id,bm1_0.id
order by count(plm1_0.id) desc,pm1_0.id desc
limit 0,50
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 37,608.6 (36,984.0~37,855.0) |
| 인덱스 적용 후 | 60,342.2 (59,502.0~61,038.0) |

**실행 계획 — 인덱스 적용 전**

```text
-> Limit: 50 row(s)  (actual time=37855..37855 rows=50 loops=1)
    -> Sort: `count(plm1_0.id)` DESC, pm1_0.id DESC, limit input to 50 row(s) per chunk  (actual time=37855..37855 rows=50 loops=1)
        -> Table scan on <temporary>  (actual time=36522..37415 rows=4.75e+6 loops=1)
            -> Aggregate using temporary table  (actual time=36522..36522 rows=4.75e+6 loops=1)
                -> Left hash join (plm1_0.product_id = pm1_0.id)  (cost=359e+9 rows=3.59e+12) (actual time=1501..11909 rows=10.3e+6 loops=1)
                    -> Nested loop inner join  (cost=972828 rows=485830) (actual time=0.187..3718 rows=4.75e+6 loops=1)
                        -> Filter: (pm1_0.deleted_at is null)  (cost=511290 rows=485830) (actual time=0.133..1306 rows=4.75e+6 loops=1)
                            -> Table scan on pm1_0  (cost=511290 rows=4.86e+6) (actual time=0.132..1093 rows=5e+6 loops=1)
                        -> Single-row index lookup on bm1_0 using PRIMARY (id=pm1_0.brand_id)  (cost=0.85 rows=1) (actual time=378e-6..399e-6 rows=1 loops=4.75e+6)
                    -> Hash
                        -> Covering index scan on plm1_0 using ux_product_like_user_product  (cost=144 rows=7.38e+6) (actual time=0.367..881 rows=7.6e+6 loops=1)
```

**실행 계획 — 인덱스 적용 후**

```text
-> Limit: 50 row(s)  (actual time=59936..59936 rows=50 loops=1)
    -> Sort: `count(plm1_0.id)` DESC, pm1_0.id DESC, limit input to 50 row(s) per chunk  (actual time=59936..59936 rows=50 loops=1)
        -> Table scan on <temporary>  (actual time=58417..59496 rows=4.75e+6 loops=1)
            -> Aggregate using temporary table  (actual time=58417..58417 rows=4.75e+6 loops=1)
                -> Nested loop left join  (cost=859734 rows=1.76e+6) (actual time=0.334..26144 rows=10.3e+6 loops=1)
                    -> Nested loop inner join  (cost=278042 rows=403284) (actual time=0.329..16982 rows=4.75e+6 loops=1)
                        -> Table scan on bm1_0  (cost=105 rows=1000) (actual time=0.0572..1.48 rows=1000 loops=1)
                        -> Index lookup on pm1_0 using ix_product_brand_latest (brand_id=bm1_0.id, deleted_at=NULL), with index condition: (pm1_0.deleted_at is null)  (cost=238 rows=403) (actual time=0.156..16.8 rows=4751 loops=1000)
                    -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pm1_0.id)  (cost=1.01 rows=4.36) (actual time=0.00151..0.00175 rows=1.53 loops=4.75e+6)
```

### L04

전체 · 딥 페이지 (`page=99`) · `latest` — `/api/v1/products?sort=latest&page=99&size=50`

```sql
select pm1_0.id,pm1_0.name,pm1_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from product pm1_0
join brand bm1_0 on bm1_0.id=pm1_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm1_0.id
where pm1_0.deleted_at is null
group by pm1_0.id,bm1_0.id
order by pm1_0.created_at desc,pm1_0.id desc
limit 4950,50
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 41,904.6 (41,552.0~42,176.0) |
| 인덱스 적용 후 | 66,412.8 (65,336.0~67,741.0) |

**실행 계획 — 인덱스 적용 전**

```text
-> Limit/Offset: 50/4950 row(s)  (actual time=42176..42176 rows=50 loops=1)
    -> Sort: pm1_0.created_at DESC, pm1_0.id DESC, limit input to 5000 row(s) per chunk  (actual time=42176..42176 rows=5000 loops=1)
        -> Table scan on <temporary>  (actual time=39929..41118 rows=4.75e+6 loops=1)
            -> Aggregate using temporary table  (actual time=39929..39929 rows=4.75e+6 loops=1)
                -> Left hash join (plm1_0.product_id = pm1_0.id)  (cost=359e+9 rows=3.59e+12) (actual time=1621..13038 rows=10.3e+6 loops=1)
                    -> Nested loop inner join  (cost=973351 rows=485830) (actual time=0.338..4071 rows=4.75e+6 loops=1)
                        -> Filter: (pm1_0.deleted_at is null)  (cost=511813 rows=485830) (actual time=0.258..1594 rows=4.75e+6 loops=1)
                            -> Table scan on pm1_0  (cost=511813 rows=4.86e+6) (actual time=0.256..1379 rows=5e+6 loops=1)
                        -> Single-row index lookup on bm1_0 using PRIMARY (id=pm1_0.brand_id)  (cost=0.85 rows=1) (actual time=389e-6..411e-6 rows=1 loops=4.75e+6)
                    -> Hash
                        -> Covering index scan on plm1_0 using ux_product_like_user_product  (cost=145 rows=7.38e+6) (actual time=1.39..986 rows=7.6e+6 loops=1)
```

**실행 계획 — 인덱스 적용 후**

```text
-> Limit/Offset: 50/4950 row(s)  (actual time=66335..66335 rows=50 loops=1)
    -> Sort: pm1_0.created_at DESC, pm1_0.id DESC, limit input to 5000 row(s) per chunk  (actual time=66335..66335 rows=5000 loops=1)
        -> Table scan on <temporary>  (actual time=64072..65305 rows=4.75e+6 loops=1)
            -> Aggregate using temporary table  (actual time=64072..64072 rows=4.75e+6 loops=1)
                -> Nested loop left join  (cost=861287 rows=1.76e+6) (actual time=0.472..27335 rows=10.3e+6 loops=1)
                    -> Nested loop inner join  (cost=279595 rows=403284) (actual time=0.466..17943 rows=4.75e+6 loops=1)
                        -> Table scan on bm1_0  (cost=105 rows=1000) (actual time=0.085..2.09 rows=1000 loops=1)
                        -> Index lookup on pm1_0 using ix_product_brand_latest (brand_id=bm1_0.id, deleted_at=NULL), with index condition: (pm1_0.deleted_at is null)  (cost=239 rows=403) (actual time=0.169..17.7 rows=4751 loops=1000)
                    -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pm1_0.id)  (cost=1.01 rows=4.36) (actual time=0.00154..0.00179 rows=1.53 loops=4.75e+6)
```

### L05

전체 · 딥 페이지 (`page=99`) · `price_asc` — `/api/v1/products?sort=price_asc&page=99&size=50`

```sql
select pm1_0.id,pm1_0.name,pm1_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from product pm1_0
join brand bm1_0 on bm1_0.id=pm1_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm1_0.id
where pm1_0.deleted_at is null
group by pm1_0.id,bm1_0.id
order by pm1_0.price,pm1_0.id desc
limit 4950,50
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 38,652.4 (38,128.0~39,168.0) |
| 인덱스 적용 후 | 60,587.4 (60,004.0~61,452.0) |

**실행 계획 — 인덱스 적용 전**

```text
-> Limit/Offset: 50/4950 row(s)  (actual time=38328..38328 rows=50 loops=1)
    -> Sort: pm1_0.price, pm1_0.id DESC, limit input to 5000 row(s) per chunk  (actual time=38328..38328 rows=5000 loops=1)
        -> Table scan on <temporary>  (actual time=36550..37409 rows=4.75e+6 loops=1)
            -> Aggregate using temporary table  (actual time=36550..36550 rows=4.75e+6 loops=1)
                -> Left hash join (plm1_0.product_id = pm1_0.id)  (cost=359e+9 rows=3.59e+12) (actual time=1529..11884 rows=10.3e+6 loops=1)
                    -> Nested loop inner join  (cost=972527 rows=485830) (actual time=0.217..3718 rows=4.75e+6 loops=1)
                        -> Filter: (pm1_0.deleted_at is null)  (cost=510989 rows=485830) (actual time=0.165..1324 rows=4.75e+6 loops=1)
                            -> Table scan on pm1_0  (cost=510989 rows=4.86e+6) (actual time=0.163..1112 rows=5e+6 loops=1)
                        -> Single-row index lookup on bm1_0 using PRIMARY (id=pm1_0.brand_id)  (cost=0.85 rows=1) (actual time=373e-6..394e-6 rows=1 loops=4.75e+6)
                    -> Hash
                        -> Covering index scan on plm1_0 using ux_product_like_user_product  (cost=144 rows=7.38e+6) (actual time=0.562..908 rows=7.6e+6 loops=1)
```

**실행 계획 — 인덱스 적용 후**

```text
-> Limit/Offset: 50/4950 row(s)  (actual time=60188..60188 rows=50 loops=1)
    -> Sort: pm1_0.price, pm1_0.id DESC, limit input to 5000 row(s) per chunk  (actual time=60188..60188 rows=5000 loops=1)
        -> Table scan on <temporary>  (actual time=58056..59171 rows=4.75e+6 loops=1)
            -> Aggregate using temporary table  (actual time=58056..58056 rows=4.75e+6 loops=1)
                -> Nested loop left join  (cost=865327 rows=1.76e+6) (actual time=0.415..26019 rows=10.3e+6 loops=1)
                    -> Nested loop inner join  (cost=283635 rows=403284) (actual time=0.397..16873 rows=4.75e+6 loops=1)
                        -> Table scan on bm1_0  (cost=105 rows=1000) (actual time=0.0587..1.57 rows=1000 loops=1)
                        -> Index lookup on pm1_0 using ix_product_brand_latest (brand_id=bm1_0.id, deleted_at=NULL), with index condition: (pm1_0.deleted_at is null)  (cost=243 rows=403) (actual time=0.15..16.7 rows=4751 loops=1000)
                    -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pm1_0.id)  (cost=1.01 rows=4.36) (actual time=0.00151..0.00175 rows=1.53 loops=4.75e+6)
```

### L06

전체 · 딥 페이지 (`page=99`) · `likes_desc` — `/api/v1/products?sort=likes_desc&page=99&size=50`

```sql
select pm1_0.id,pm1_0.name,pm1_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from product pm1_0
join brand bm1_0 on bm1_0.id=pm1_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm1_0.id
where pm1_0.deleted_at is null
group by pm1_0.id,bm1_0.id
order by count(plm1_0.id) desc,pm1_0.id desc
limit 4950,50
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 38,378.8 (37,783.0~38,842.0) |
| 인덱스 적용 후 | 60,413.8 (59,842.0~61,197.0) |

**실행 계획 — 인덱스 적용 전**

```text
-> Limit/Offset: 50/4950 row(s)  (actual time=38327..38327 rows=50 loops=1)
    -> Sort: `count(plm1_0.id)` DESC, pm1_0.id DESC, limit input to 5000 row(s) per chunk  (actual time=38327..38327 rows=5000 loops=1)
        -> Table scan on <temporary>  (actual time=36521..37461 rows=4.75e+6 loops=1)
            -> Aggregate using temporary table  (actual time=36521..36521 rows=4.75e+6 loops=1)
                -> Left hash join (plm1_0.product_id = pm1_0.id)  (cost=359e+9 rows=3.59e+12) (actual time=1594..12001 rows=10.3e+6 loops=1)
                    -> Nested loop inner join  (cost=972199 rows=485830) (actual time=0.321..3832 rows=4.75e+6 loops=1)
                        -> Filter: (pm1_0.deleted_at is null)  (cost=510661 rows=485830) (actual time=0.234..1430 rows=4.75e+6 loops=1)
                            -> Table scan on pm1_0  (cost=510661 rows=4.86e+6) (actual time=0.233..1216 rows=5e+6 loops=1)
                        -> Single-row index lookup on bm1_0 using PRIMARY (id=pm1_0.brand_id)  (cost=0.85 rows=1) (actual time=374e-6..395e-6 rows=1 loops=4.75e+6)
                    -> Hash
                        -> Covering index scan on plm1_0 using ux_product_like_user_product  (cost=144 rows=7.38e+6) (actual time=0.49..963 rows=7.6e+6 loops=1)
```

**실행 계획 — 인덱스 적용 후**

```text
-> Limit/Offset: 50/4950 row(s)  (actual time=61197..61197 rows=50 loops=1)
    -> Sort: `count(plm1_0.id)` DESC, pm1_0.id DESC, limit input to 5000 row(s) per chunk  (actual time=61196..61196 rows=5000 loops=1)
        -> Table scan on <temporary>  (actual time=59049..60195 rows=4.75e+6 loops=1)
            -> Aggregate using temporary table  (actual time=59049..59049 rows=4.75e+6 loops=1)
                -> Nested loop left join  (cost=864883 rows=1.76e+6) (actual time=1.55..26640 rows=10.3e+6 loops=1)
                    -> Nested loop inner join  (cost=283190 rows=403284) (actual time=1.54..17401 rows=4.75e+6 loops=1)
                        -> Table scan on bm1_0  (cost=104 rows=1000) (actual time=0.072..1.99 rows=1000 loops=1)
                        -> Index lookup on pm1_0 using ix_product_brand_latest (brand_id=bm1_0.id, deleted_at=NULL), with index condition: (pm1_0.deleted_at is null)  (cost=243 rows=403) (actual time=0.158..17.2 rows=4751 loops=1000)
                    -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pm1_0.id)  (cost=1.01 rows=4.36) (actual time=0.00152..0.00176 rows=1.53 loops=4.75e+6)
```

### L07

1번 브랜드 · 첫 페이지 (`page=0`) · `latest` — `/api/v1/products?sort=latest&page=0&size=50&brandId=1`

```sql
select pm1_0.id,pm1_0.name,pm1_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from product pm1_0
join brand bm1_0 on bm1_0.id=pm1_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm1_0.id
where pm1_0.deleted_at is null and pm1_0.brand_id=1
group by pm1_0.id,bm1_0.id
order by pm1_0.created_at desc,pm1_0.id desc
limit 0,50
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 5,605.4 (5,416.0~5,943.0) |
| 인덱스 적용 후 | 3,216.2 (3,129.0~3,477.0) |

**실행 계획 — 인덱스 적용 전**

```text
-> Limit: 50 row(s)  (actual time=5569..5569 rows=50 loops=1)
    -> Sort: pm1_0.created_at DESC, pm1_0.id DESC, limit input to 50 row(s) per chunk  (actual time=5569..5569 rows=50 loops=1)
        -> Table scan on <temporary>  (actual time=5429..5510 rows=474993 loops=1)
            -> Aggregate using temporary table  (actual time=5429..5429 rows=474993 loops=1)
                -> Left hash join (plm1_0.product_id = pm1_0.id)  (cost=35.9e+9 rows=359e+9) (actual time=1474..3614 rows=999903 loops=1)
                    -> Filter: ((pm1_0.brand_id = 1) and (pm1_0.deleted_at is null))  (cost=492326 rows=48583) (actual time=0.0564..1102 rows=474993 loops=1)
                        -> Table scan on pm1_0  (cost=492326 rows=4.86e+6) (actual time=0.0477..916 rows=5e+6 loops=1)
                    -> Hash
                        -> Covering index scan on plm1_0 using ux_product_like_user_product  (cost=112 rows=7.38e+6) (actual time=0.223..886 rows=7.6e+6 loops=1)
```

**실행 계획 — 인덱스 적용 후**

```text
-> Limit: 50 row(s)  (actual time=3140..3140 rows=50 loops=1)
    -> Sort: pm1_0.created_at DESC, pm1_0.id DESC, limit input to 50 row(s) per chunk  (actual time=3140..3140 rows=50 loops=1)
        -> Table scan on <temporary>  (actual time=3004..3082 rows=474993 loops=1)
            -> Aggregate using temporary table  (actual time=3004..3004 rows=474992 loops=1)
                -> Nested loop left join  (cost=1.6e+6 rows=4.48e+6) (actual time=0.404..1481 rows=999903 loops=1)
                    -> Index lookup on pm1_0 using ix_product_brand_latest (brand_id=1, deleted_at=NULL), with index condition: (pm1_0.deleted_at is null)  (cost=122276 rows=1.03e+6) (actual time=0.389..878 rows=474993 loops=1)
                    -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pm1_0.id)  (cost=1.01 rows=4.36) (actual time=896e-6..0.00111 rows=1.46 loops=474993)
```

### L08

1번 브랜드 · 첫 페이지 (`page=0`) · `price_asc` — `/api/v1/products?sort=price_asc&page=0&size=50&brandId=1`

```sql
select pm1_0.id,pm1_0.name,pm1_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from product pm1_0
join brand bm1_0 on bm1_0.id=pm1_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm1_0.id
where pm1_0.deleted_at is null and pm1_0.brand_id=1
group by pm1_0.id,bm1_0.id
order by pm1_0.price,pm1_0.id desc
limit 0,50
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 5,222.6 (5,088.0~5,281.0) |
| 인덱스 적용 후 | 2,960.6 (2,915.0~3,102.0) |

**실행 계획 — 인덱스 적용 전**

```text
-> Limit: 50 row(s)  (actual time=5281..5281 rows=50 loops=1)
    -> Sort: pm1_0.price, pm1_0.id DESC, limit input to 50 row(s) per chunk  (actual time=5281..5281 rows=50 loops=1)
        -> Table scan on <temporary>  (actual time=5168..5242 rows=474993 loops=1)
            -> Aggregate using temporary table  (actual time=5168..5168 rows=474992 loops=1)
                -> Left hash join (plm1_0.product_id = pm1_0.id)  (cost=35.9e+9 rows=359e+9) (actual time=1431..3510 rows=999903 loops=1)
                    -> Filter: ((pm1_0.brand_id = 1) and (pm1_0.deleted_at is null))  (cost=492326 rows=48583) (actual time=0.101..1071 rows=474993 loops=1)
                        -> Table scan on pm1_0  (cost=492326 rows=4.86e+6) (actual time=0.0958..882 rows=5e+6 loops=1)
                    -> Hash
                        -> Covering index scan on plm1_0 using ux_product_like_user_product  (cost=111 rows=7.38e+6) (actual time=0.247..831 rows=7.6e+6 loops=1)
```

**실행 계획 — 인덱스 적용 후**

```text
-> Limit: 50 row(s)  (actual time=2922..2922 rows=50 loops=1)
    -> Sort: pm1_0.price, pm1_0.id DESC, limit input to 50 row(s) per chunk  (actual time=2922..2922 rows=50 loops=1)
        -> Table scan on <temporary>  (actual time=2811..2883 rows=474993 loops=1)
            -> Aggregate using temporary table  (actual time=2811..2811 rows=474993 loops=1)
                -> Nested loop left join  (cost=1.6e+6 rows=4.48e+6) (actual time=0.401..1448 rows=999903 loops=1)
                    -> Index lookup on pm1_0 using ix_product_brand_latest (brand_id=1, deleted_at=NULL), with index condition: (pm1_0.deleted_at is null)  (cost=122276 rows=1.03e+6) (actual time=0.394..850 rows=474993 loops=1)
                    -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pm1_0.id)  (cost=1.01 rows=4.36) (actual time=894e-6..0.0011 rows=1.46 loops=474993)
```

### L09

1번 브랜드 · 첫 페이지 (`page=0`) · `likes_desc` — `/api/v1/products?sort=likes_desc&page=0&size=50&brandId=1`

```sql
select pm1_0.id,pm1_0.name,pm1_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from product pm1_0
join brand bm1_0 on bm1_0.id=pm1_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm1_0.id
where pm1_0.deleted_at is null and pm1_0.brand_id=1
group by pm1_0.id,bm1_0.id
order by count(plm1_0.id) desc,pm1_0.id desc
limit 0,50
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 5,144.8 (5,063.0~5,229.0) |
| 인덱스 적용 후 | 2,953.0 (2,910.0~3,059.0) |

**실행 계획 — 인덱스 적용 전**

```text
-> Limit: 50 row(s)  (actual time=5229..5229 rows=50 loops=1)
    -> Sort: `count(plm1_0.id)` DESC, pm1_0.id DESC, limit input to 50 row(s) per chunk  (actual time=5229..5229 rows=50 loops=1)
        -> Table scan on <temporary>  (actual time=5113..5189 rows=474993 loops=1)
            -> Aggregate using temporary table  (actual time=5113..5113 rows=474993 loops=1)
                -> Left hash join (plm1_0.product_id = pm1_0.id)  (cost=35.9e+9 rows=359e+9) (actual time=1376..3445 rows=999903 loops=1)
                    -> Filter: ((pm1_0.brand_id = 1) and (pm1_0.deleted_at is null))  (cost=492326 rows=48583) (actual time=0.0646..1043 rows=474993 loops=1)
                        -> Table scan on pm1_0  (cost=492326 rows=4.86e+6) (actual time=0.059..851 rows=5e+6 loops=1)
                    -> Hash
                        -> Covering index scan on plm1_0 using ux_product_like_user_product  (cost=111 rows=7.38e+6) (actual time=0.168..800 rows=7.6e+6 loops=1)
```

**실행 계획 — 인덱스 적용 후**

```text
-> Limit: 50 row(s)  (actual time=2910..2910 rows=50 loops=1)
    -> Sort: `count(plm1_0.id)` DESC, pm1_0.id DESC, limit input to 50 row(s) per chunk  (actual time=2910..2910 rows=50 loops=1)
        -> Table scan on <temporary>  (actual time=2797..2870 rows=474993 loops=1)
            -> Aggregate using temporary table  (actual time=2797..2797 rows=474993 loops=1)
                -> Nested loop left join  (cost=1.6e+6 rows=4.48e+6) (actual time=0.399..1439 rows=999903 loops=1)
                    -> Index lookup on pm1_0 using ix_product_brand_latest (brand_id=1, deleted_at=NULL), with index condition: (pm1_0.deleted_at is null)  (cost=122276 rows=1.03e+6) (actual time=0.392..836 rows=474993 loops=1)
                    -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pm1_0.id)  (cost=1.01 rows=4.36) (actual time=902e-6..0.00111 rows=1.46 loops=474993)
```

### L10

1번 브랜드 · 딥 페이지 (`page=99`) · `latest` — `/api/v1/products?sort=latest&page=99&size=50&brandId=1`

```sql
select pm1_0.id,pm1_0.name,pm1_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from product pm1_0
join brand bm1_0 on bm1_0.id=pm1_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm1_0.id
where pm1_0.deleted_at is null and pm1_0.brand_id=1
group by pm1_0.id,bm1_0.id
order by pm1_0.created_at desc,pm1_0.id desc
limit 4950,50
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 5,595.2 (5,394.0~5,720.0) |
| 인덱스 적용 후 | 3,165.2 (3,117.0~3,267.0) |

**실행 계획 — 인덱스 적용 전**

```text
-> Limit/Offset: 50/4950 row(s)  (actual time=5630..5630 rows=50 loops=1)
    -> Sort: pm1_0.created_at DESC, pm1_0.id DESC, limit input to 5000 row(s) per chunk  (actual time=5629..5630 rows=5000 loops=1)
        -> Table scan on <temporary>  (actual time=5490..5567 rows=474993 loops=1)
            -> Aggregate using temporary table  (actual time=5490..5490 rows=474993 loops=1)
                -> Left hash join (plm1_0.product_id = pm1_0.id)  (cost=35.9e+9 rows=359e+9) (actual time=1415..3605 rows=999903 loops=1)
                    -> Filter: ((pm1_0.brand_id = 1) and (pm1_0.deleted_at is null))  (cost=492326 rows=48583) (actual time=0.07..1109 rows=474993 loops=1)
                        -> Table scan on pm1_0  (cost=492326 rows=4.86e+6) (actual time=0.0543..920 rows=5e+6 loops=1)
                    -> Hash
                        -> Covering index scan on plm1_0 using ux_product_like_user_product  (cost=112 rows=7.38e+6) (actual time=0.221..819 rows=7.6e+6 loops=1)
```

**실행 계획 — 인덱스 적용 후**

```text
-> Limit/Offset: 50/4950 row(s)  (actual time=3122..3122 rows=50 loops=1)
    -> Sort: pm1_0.created_at DESC, pm1_0.id DESC, limit input to 5000 row(s) per chunk  (actual time=3122..3122 rows=5000 loops=1)
        -> Table scan on <temporary>  (actual time=2982..3056 rows=474993 loops=1)
            -> Aggregate using temporary table  (actual time=2982..2982 rows=474992 loops=1)
                -> Nested loop left join  (cost=1.6e+6 rows=4.48e+6) (actual time=0.407..1461 rows=999903 loops=1)
                    -> Index lookup on pm1_0 using ix_product_brand_latest (brand_id=1, deleted_at=NULL), with index condition: (pm1_0.deleted_at is null)  (cost=122276 rows=1.03e+6) (actual time=0.4..855 rows=474993 loops=1)
                    -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pm1_0.id)  (cost=1.01 rows=4.36) (actual time=905e-6..0.00112 rows=1.46 loops=474993)
```

### L11

1번 브랜드 · 딥 페이지 (`page=99`) · `price_asc` — `/api/v1/products?sort=price_asc&page=99&size=50&brandId=1`

```sql
select pm1_0.id,pm1_0.name,pm1_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from product pm1_0
join brand bm1_0 on bm1_0.id=pm1_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm1_0.id
where pm1_0.deleted_at is null and pm1_0.brand_id=1
group by pm1_0.id,bm1_0.id
order by pm1_0.price,pm1_0.id desc
limit 4950,50
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 5,305.6 (5,231.0~5,440.0) |
| 인덱스 적용 후 | 2,975.0 (2,965.0~3,007.0) |

**실행 계획 — 인덱스 적용 전**

```text
-> Limit/Offset: 50/4950 row(s)  (actual time=5440..5440 rows=50 loops=1)
    -> Sort: pm1_0.price, pm1_0.id DESC, limit input to 5000 row(s) per chunk  (actual time=5440..5440 rows=5000 loops=1)
        -> Table scan on <temporary>  (actual time=5279..5351 rows=474993 loops=1)
            -> Aggregate using temporary table  (actual time=5279..5279 rows=474993 loops=1)
                -> Left hash join (plm1_0.product_id = pm1_0.id)  (cost=35.9e+9 rows=359e+9) (actual time=1455..3586 rows=999903 loops=1)
                    -> Filter: ((pm1_0.brand_id = 1) and (pm1_0.deleted_at is null))  (cost=492326 rows=48583) (actual time=0.0683..1104 rows=474993 loops=1)
                        -> Table scan on pm1_0  (cost=492326 rows=4.86e+6) (actual time=0.0556..912 rows=5e+6 loops=1)
                    -> Hash
                        -> Covering index scan on plm1_0 using ux_product_like_user_product  (cost=111 rows=7.38e+6) (actual time=0.278..840 rows=7.6e+6 loops=1)
```

**실행 계획 — 인덱스 적용 후**

```text
-> Limit/Offset: 50/4950 row(s)  (actual time=2966..2966 rows=50 loops=1)
    -> Sort: pm1_0.price, pm1_0.id DESC, limit input to 5000 row(s) per chunk  (actual time=2966..2966 rows=5000 loops=1)
        -> Table scan on <temporary>  (actual time=2803..2873 rows=474993 loops=1)
            -> Aggregate using temporary table  (actual time=2803..2803 rows=474993 loops=1)
                -> Nested loop left join  (cost=1.6e+6 rows=4.48e+6) (actual time=0.38..1443 rows=999903 loops=1)
                    -> Index lookup on pm1_0 using ix_product_brand_latest (brand_id=1, deleted_at=NULL), with index condition: (pm1_0.deleted_at is null)  (cost=122276 rows=1.03e+6) (actual time=0.374..846 rows=474993 loops=1)
                    -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pm1_0.id)  (cost=1.01 rows=4.36) (actual time=891e-6..0.0011 rows=1.46 loops=474993)
```

### L12

1번 브랜드 · 딥 페이지 (`page=99`) · `likes_desc` — `/api/v1/products?sort=likes_desc&page=99&size=50&brandId=1`

```sql
select pm1_0.id,pm1_0.name,pm1_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from product pm1_0
join brand bm1_0 on bm1_0.id=pm1_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm1_0.id
where pm1_0.deleted_at is null and pm1_0.brand_id=1
group by pm1_0.id,bm1_0.id
order by count(plm1_0.id) desc,pm1_0.id desc
limit 4950,50
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 5,236.6 (5,163.0~5,423.0) |
| 인덱스 적용 후 | 2,982.0 (2,936.0~3,093.0) |

**실행 계획 — 인덱스 적용 전**

```text
-> Limit/Offset: 50/4950 row(s)  (actual time=5194..5194 rows=50 loops=1)
    -> Sort: `count(plm1_0.id)` DESC, pm1_0.id DESC, limit input to 5000 row(s) per chunk  (actual time=5194..5194 rows=5000 loops=1)
        -> Table scan on <temporary>  (actual time=5052..5124 rows=474993 loops=1)
            -> Aggregate using temporary table  (actual time=5052..5052 rows=474993 loops=1)
                -> Left hash join (plm1_0.product_id = pm1_0.id)  (cost=35.9e+9 rows=359e+9) (actual time=1375..3411 rows=999903 loops=1)
                    -> Filter: ((pm1_0.brand_id = 1) and (pm1_0.deleted_at is null))  (cost=492326 rows=48583) (actual time=0.0923..1036 rows=474993 loops=1)
                        -> Table scan on pm1_0  (cost=492326 rows=4.86e+6) (actual time=0.0867..849 rows=5e+6 loops=1)
                    -> Hash
                        -> Covering index scan on plm1_0 using ux_product_like_user_product  (cost=111 rows=7.38e+6) (actual time=0.208..802 rows=7.6e+6 loops=1)
```

**실행 계획 — 인덱스 적용 후**

```text
-> Limit/Offset: 50/4950 row(s)  (actual time=2946..2946 rows=50 loops=1)
    -> Sort: `count(plm1_0.id)` DESC, pm1_0.id DESC, limit input to 5000 row(s) per chunk  (actual time=2945..2946 rows=5000 loops=1)
        -> Table scan on <temporary>  (actual time=2802..2872 rows=474993 loops=1)
            -> Aggregate using temporary table  (actual time=2802..2802 rows=474993 loops=1)
                -> Nested loop left join  (cost=1.6e+6 rows=4.48e+6) (actual time=0.399..1441 rows=999903 loops=1)
                    -> Index lookup on pm1_0 using ix_product_brand_latest (brand_id=1, deleted_at=NULL), with index condition: (pm1_0.deleted_at is null)  (cost=122276 rows=1.03e+6) (actual time=0.392..842 rows=474993 loops=1)
                    -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pm1_0.id)  (cost=1.01 rows=4.36) (actual time=895e-6..0.0011 rows=1.46 loops=474993)
```

## 6. count 쿼리 상세

### C01

전체 — L01~L06 의 `totalCount`

```sql
select count(pm1_0.id) from product pm1_0 where pm1_0.deleted_at is null
```

> 짝이 되는 목록 쿼리가 끝나지 않아 general log 로 잡지 못했다. C02 에서 브랜드 조건만 뺀 형태로 추론.

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 968.6 (956.0~998.0) |
| 인덱스 적용 후 | 858.8 (854.0~867.0) |

**실행 계획 — 인덱스 적용 전**

```text
-> Aggregate: count(pm1_0.id)  (cost=540909 rows=1) (actual time=966..966 rows=1 loops=1)
    -> Filter: (pm1_0.deleted_at is null)  (cost=492326 rows=485830) (actual time=0.426..830 rows=4.75e+6 loops=1)
        -> Table scan on pm1_0  (cost=492326 rows=4.86e+6) (actual time=0.425..618 rows=5e+6 loops=1)
```

**실행 계획 — 인덱스 적용 후**

```text
-> Aggregate: count(pm1_0.id)  (cost=470087 rows=1) (actual time=857..857 rows=1 loops=1)
    -> Filter: (pm1_0.deleted_at is null)  (cost=239671 rows=2.3e+6) (actual time=0.334..721 rows=4.75e+6 loops=1)
        -> Covering index lookup on pm1_0 using ix_product_latest (deleted_at=NULL)  (cost=239671 rows=2.3e+6) (actual time=0.334..519 rows=4.75e+6 loops=1)
```

### C02

1번 브랜드 — L07~L12 의 `totalCount`

```sql
select count(pm1_0.id) from product pm1_0 where pm1_0.deleted_at is null and pm1_0.brand_id=1
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 896.6 (876.0~963.0) |
| 인덱스 적용 후 | 98.1 (97.1~99.5) |

**실행 계획 — 인덱스 적용 전**

```text
-> Aggregate: count(pm1_0.id)  (cost=497185 rows=1) (actual time=885..885 rows=1 loops=1)
    -> Filter: ((pm1_0.brand_id = 1) and (pm1_0.deleted_at is null))  (cost=492326 rows=48583) (actual time=0.307..871 rows=474993 loops=1)
        -> Table scan on pm1_0  (cost=492326 rows=4.86e+6) (actual time=0.304..680 rows=5e+6 loops=1)
```

**실행 계획 — 인덱스 적용 후**

```text
-> Aggregate: count(pm1_0.id)  (cost=210711 rows=1) (actual time=98.7..98.7 rows=1 loops=1)
    -> Filter: (pm1_0.deleted_at is null)  (cost=107926 rows=1.03e+6) (actual time=0.322..83.7 rows=474993 loops=1)
        -> Covering index lookup on pm1_0 using ix_product_brand_latest (brand_id=1, deleted_at=NULL)  (cost=107926 rows=1.03e+6) (actual time=0.322..63.2 rows=474993 loops=1)
```

## 7. 분석

### 7-1. 요약

| 묶음 | 인덱스 적용 전 | 인덱스 적용 후 | 변화 |
|---|---|---|---|
| 전체 목록 (L01~L06) | 37.6~41.9초 | 60.1~66.4초 | 약 1.6배 느려짐 |
| 1번 브랜드 목록 (L07~L12) | 5.1~5.6초 | 2.95~3.2초 | 약 43% 빨라짐 |
| 전체 count (C01) | 969ms | 859ms | 11% 빨라짐 |
| 1번 브랜드 count (C02) | 897ms | 98ms | 약 9배 빨라짐 |

- 인덱스 적용 전후 모두 **정렬(`latest`/`price_asc`/`likes_desc`)과 페이지(첫/딥)에 따른 차이가 거의 없다.** 시간은 대상 행 수(전체 475만 / 1번 브랜드 47만)로만 갈린다.
- 정렬용 인덱스(`ix_product_latest`, `ix_product_price`)는 어떤 목록 쿼리에서도 쓰이지 않았다.

### 7-2. 공통 병목: 전 상품 집계 후 정렬

목록 쿼리는 `product` ⋈ `brand` ⟕ `product_like` 를 `GROUP BY pm1_0.id, bm1_0.id` 로 묶은 뒤 정렬하고 50건을 자른다.

- `GROUP BY` 가 두 테이블의 컬럼이라 MySQL 은 조인 결과 전체를 임시 테이블에 모아 집계한다 (`Aggregate using temporary table`).
- 정렬은 집계가 끝난 뒤에야 할 수 있다. 그래서 정렬 키에 인덱스가 있어도 "인덱스 순서로 읽다가 50건에서 멈추기"가 불가능하고, 대상 상품 전부를 읽고 집계한다.
- `likes_desc` 의 정렬 키 `count(plm1_0.id)` 는 집계 결과라 애초에 인덱스로 정렬할 수 없다.
- OFFSET 4,950 의 딥 페이지도 집계가 끝난 결과에서 자르는 것이라 첫 페이지와 시간이 같다.

### 7-3. 인덱스 적용 전

L01 (전체, `latest`) 실행 계획 단계별 누적 시간:

| 단계 | 누적 시간 | 처리 행 |
|---|---|---|
| `product` 풀스캔 + `deleted_at is null` 필터 | 1.6초 | 500만 → 475만 |
| `brand` PK 조회 (상품마다) | 4.0초 | 475만 회 |
| `product_like` 해시 조인 (`ux_product_like_user_product` 커버링 스캔으로 760만 건 읽음) | 13.3초 | 1,030만 |
| 임시 테이블 집계 | 41.7초 | 475만 그룹 |
| 정렬 후 50건 | 43.4초 | 50 |

- 가장 큰 비용은 1,030만 행을 475만 그룹으로 묶는 임시 테이블 집계(약 28초)다. 475만 그룹이면 임시 테이블이 메모리 한도를 넘어 디스크로 넘어갔을 가능성이 있다 (확인하지 않음).
- 1번 브랜드(L09)는 같은 구조에서 대상이 47만 건이라 5초대다. 다만 `brand_id` 인덱스가 없어 `product` 500만 건 풀스캔과 `product_like` 760만 건 해시 테이블 생성은 그대로 한다.
- count 는 `product` 500만 건 풀스캔이다 (C01·C02 모두 약 0.9~1초).

### 7-4. 인덱스 적용 후

**전체 목록이 느려진 이유 (L01)**

| 단계 | 누적 시간 | 처리 행 |
|---|---|---|
| `brand` 풀스캔 → 브랜드마다 `ix_product_brand_latest` 로 `product` 조회 | 17.1초 | 1,000회 × 평균 4,751건 = 475만 |
| 상품마다 `ix_product_like_product` 커버링 조회 (중첩 루프) | 26.3초 | 475만 회 → 1,030만 |
| 임시 테이블 집계 | 61.1초 | 475만 그룹 |
| 정렬 후 50건 | 62.9초 | 50 |

- `brand_id` 인덱스가 생기자 옵티마이저가 조인 순서를 `brand` → `product` 로 바꾸고, 해시 조인 대신 인덱스 조회 중첩 루프를 골랐다.
- `ix_product_brand_latest` 에는 `name`·`price` 가 없어 상품마다 PK 로 다시 찾아간다. 475만 번의 랜덤 접근이 순차 풀스캔(1.6초)보다 훨씬 느리다 (17초).
- `product_like` 도 475만 번의 인덱스 조회가 한 번의 해시 테이블 생성보다 느리다.
- 집계 단계도 28초 → 35초로 늘었다. 행이 브랜드 순서로 들어와 상품 ID 순서와 어긋난 영향으로 추정한다 (확인하지 않음).
- 테이블의 95% 를 읽는 쿼리인데 옵티마이저가 인덱스 경로 비용을 낮게 추정했다. 대상 비율이 높은 조회에서 인덱스가 오히려 손해가 되는 경우다.

**1번 브랜드 목록이 빨라진 이유 (L09)**

| 단계 | 인덱스 적용 전 | 인덱스 적용 후 |
|---|---|---|
| 대상 상품 고르기 | `product` 500만 건 풀스캔 (1.0초) | `ix_product_brand_latest` 범위 조회 47만 건 (0.84초) |
| 좋아요 붙이기 | 760만 건 해시 조인 (3.4초까지) | 47만 번 `ix_product_like_product` 커버링 조회 (1.4초까지) |
| 집계 + 정렬 | 5.2초까지 | 2.9초까지 |

- 대상이 전체의 10% 라 인덱스로 좁히는 게 이득이었고, 특히 `product_like` 760만 건 전체를 읽지 않게 된 효과가 크다.
- 그래도 47만 건 집계는 남아 3초 안팎이 한계다. 정렬·페이지에 따른 차이도 여전히 없다.

**count**

- C02 는 `ix_product_brand_latest` 의 앞부분 `(brand_id, deleted_at)` 만으로 범위를 잡고 인덱스만 읽는다 (커버링). 47만 건만 세서 98ms.
- C01 은 `ix_product_latest` 의 `deleted_at` 부분으로 커버링 스캔하지만 475만 건을 다 세야 해서 테이블 풀스캔보다 조금 빠른 정도다.

### 7-5. 결론과 다음 단계

- 현재 쿼리 구조(전 상품 집계 후 정렬)에서는 인덱스만으로 목록 조회를 빠르게 할 수 없다. 대상 범위를 좁힐 수 있을 때(브랜드 필터, count)만 효과가 있고, 전체 조회는 오히려 나빠졌다.
- 인덱스가 정렬에 쓰이려면 집계 전에 페이지를 정해야 한다.
  - `latest`·`price_asc`: 인덱스 순서로 페이지의 상품 ID 50개를 먼저 고르고, 그 50건에만 브랜드 조인·좋아요 집계를 한다.
  - `likes_desc`: 좋아요 수를 상품에 저장(비정규화)하고 그 컬럼에 인덱스를 둬야 한다. 좋아요 등록·취소의 쓰기 경로가 바뀌는 변경이라 따로 정한다.
- 전체 count(C01)는 475만 건을 세는 비용이 남는다. 정확한 `totalCount` 가 꼭 필요한지는 별도로 검토할 수 있다.

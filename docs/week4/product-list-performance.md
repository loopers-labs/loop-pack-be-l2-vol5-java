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
| 인덱스 적용 후 | (적용한 인덱스를 여기에 적는다) |

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
| L01 | 전체 | 첫 페이지 | `latest` | 41,410.4 (40,286.0~43,414.0) | - |
| L02 | 전체 | 첫 페이지 | `price_asc` | 37,686.8 (37,358.0~38,224.0) | - |
| L03 | 전체 | 첫 페이지 | `likes_desc` | 37,608.6 (36,984.0~37,855.0) | - |
| L04 | 전체 | 딥 페이지 | `latest` | 41,904.6 (41,552.0~42,176.0) | - |
| L05 | 전체 | 딥 페이지 | `price_asc` | 38,652.4 (38,128.0~39,168.0) | - |
| L06 | 전체 | 딥 페이지 | `likes_desc` | 38,378.8 (37,783.0~38,842.0) | - |
| L07 | 1번 브랜드 | 첫 페이지 | `latest` | 5,605.4 (5,416.0~5,943.0) | - |
| L08 | 1번 브랜드 | 첫 페이지 | `price_asc` | 5,222.6 (5,088.0~5,281.0) | - |
| L09 | 1번 브랜드 | 첫 페이지 | `likes_desc` | 5,144.8 (5,063.0~5,229.0) | - |
| L10 | 1번 브랜드 | 딥 페이지 | `latest` | 5,595.2 (5,394.0~5,720.0) | - |
| L11 | 1번 브랜드 | 딥 페이지 | `price_asc` | 5,305.6 (5,231.0~5,440.0) | - |
| L12 | 1번 브랜드 | 딥 페이지 | `likes_desc` | 5,236.6 (5,163.0~5,423.0) | - |

### 4-2. count 쿼리 실행 시간 (평균, ms)

| ID | 브랜드 | 인덱스 적용 전 | 인덱스 적용 후 |
|---|---|---|---|
| C01 | 전체 | 968.6 (956.0~998.0) | - |
| C02 | 1번 브랜드 | 896.6 (876.0~963.0) | - |

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
| 인덱스 적용 후 | - |

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
(미측정)
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
| 인덱스 적용 후 | - |

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
(미측정)
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
| 인덱스 적용 후 | - |

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
(미측정)
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
| 인덱스 적용 후 | - |

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
(미측정)
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
| 인덱스 적용 후 | - |

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
(미측정)
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
| 인덱스 적용 후 | - |

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
(미측정)
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
| 인덱스 적용 후 | - |

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
(미측정)
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
| 인덱스 적용 후 | - |

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
(미측정)
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
| 인덱스 적용 후 | - |

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
(미측정)
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
| 인덱스 적용 후 | - |

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
(미측정)
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
| 인덱스 적용 후 | - |

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
(미측정)
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
| 인덱스 적용 후 | - |

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
(미측정)
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
| 인덱스 적용 후 | - |

**실행 계획 — 인덱스 적용 전**

```text
-> Aggregate: count(pm1_0.id)  (cost=540909 rows=1) (actual time=966..966 rows=1 loops=1)
    -> Filter: (pm1_0.deleted_at is null)  (cost=492326 rows=485830) (actual time=0.426..830 rows=4.75e+6 loops=1)
        -> Table scan on pm1_0  (cost=492326 rows=4.86e+6) (actual time=0.425..618 rows=5e+6 loops=1)
```

**실행 계획 — 인덱스 적용 후**

```text
(미측정)
```

### C02

1번 브랜드 — L07~L12 의 `totalCount`

```sql
select count(pm1_0.id) from product pm1_0 where pm1_0.deleted_at is null and pm1_0.brand_id=1
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 896.6 (876.0~963.0) |
| 인덱스 적용 후 | - |

**실행 계획 — 인덱스 적용 전**

```text
-> Aggregate: count(pm1_0.id)  (cost=497185 rows=1) (actual time=885..885 rows=1 loops=1)
    -> Filter: ((pm1_0.brand_id = 1) and (pm1_0.deleted_at is null))  (cost=492326 rows=48583) (actual time=0.307..871 rows=474993 loops=1)
        -> Table scan on pm1_0  (cost=492326 rows=4.86e+6) (actual time=0.304..680 rows=5e+6 loops=1)
```

**실행 계획 — 인덱스 적용 후**

```text
(미측정)
```

## 7. 분석

(측정 후 케이스별로 무엇이 병목이었고 인덱스가 무엇을 바꿨는지 적는다)

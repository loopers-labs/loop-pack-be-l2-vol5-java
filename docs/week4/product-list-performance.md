# 상품 목록 조회 성능 — 인덱스 적용 전후 비교

`GET /api/v1/products` (FR-PRODUCT-01, EP-02) 를 대량 데이터에서 측정하고, 인덱스 적용 전후를 케이스별로 비교한다.
같은 케이스를 PostgreSQL 에서도 인덱스 적용 상태로 측정해 함께 적는다.
`latest`·`price_asc` 쿼리를 바꾼 뒤 MySQL·PostgreSQL 에서 다시 잰 결과는 8장에 적는다.
PostgreSQL 에 부분 인덱스를 더한 뒤 다시 잰 결과는 9장에 적는다.

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
| PostgreSQL | PostgreSQL 14.24 (docker `docker-postgres-1`, `local-pg` 프로필), `shared_buffers` 1GB, `shm_size` 1g. 나머지는 기본값 (`work_mem` 4MB, `max_parallel_workers_per_gather` 2, JIT 켜짐) |
| PostgreSQL 데이터 | `docs/week4/seed-pg.sql` 로 생성. 규모·분포는 `seed.sql` 과 같지만 무작위 값이라 MySQL 과 행 단위로 같지는 않다. 브랜드 1,000 / 유저 1,000,000 / 상품 5,000,000 (삭제 250,257) / 좋아요 7,514,034. `brandId=1` 은 삭제되지 않은 상품 474,476개, 좋아요 1,000개 이상 상품 37개, 최대 좋아요 10,061 |
| PostgreSQL 인덱스 | "인덱스 적용 후" 와 같다. PostgreSQL 은 인덱스 적용 상태만 측정했다 |
| PostgreSQL 부분 인덱스 | 9장 측정에만 해당. 위에 더해 `product` 에 `WHERE deleted_at IS NULL` 부분 인덱스 4개 (9-1). DB 에 직접 만들었고 엔티티에는 없다 |

## 2. 측정 방법

- `docs/week4/bench.py` 로 측정한다. SQL 은 이 문서의 케이스별 `sql` 블록을 그대로 읽어 쓴다.
- 케이스마다 `EXPLAIN ANALYZE` 를 워밍업 1회(버림) + 측정 5회 실행한다. 케이스는 L01 → L12 → C01 → C02 순으로 하나씩, 병렬 없이 돌린다.
- 실행 시간은 `EXPLAIN ANALYZE` 맨 위 노드의 `actual time=시작..끝` 중 끝 값(ms)이다. 서버 안에서 잰 값이라 `docker exec`·클라이언트 비용이 섞이지 않는다. 행마다 시간을 재는 계측 비용이 붙어 실제 실행보다 조금 크게 나오지만, 전후 비교는 같은 방식이라 공정하다.
- 표에는 측정 5회의 평균을 적고, 최소~최대를 괄호에 함께 적는다. 실행 계획은 5번째 측정의 결과를 붙인다.
- 원본 결과는 `docs/week4/results/<단계>/` 에 남는다 (`summary.tsv`, `<ID>.plan.txt`).
- SQL 은 앱(`local` 프로필)을 띄워 각 URL 을 호출하고 MySQL general log 로 잡은 실제 SQL 이다. 실제로는 한 줄이고, 읽기 쉽게 줄만 바꿨다.
- 요청 하나는 요청자 조회(`user` PK 조회) → 목록 쿼리 → count 쿼리 순으로 나간다. API 응답 시간은 목록 쿼리 + count 쿼리 시간이 대부분이다.
- 캐시 적용 효과(API 응답 시간)는 이번 측정 범위 밖이다.
- PostgreSQL 은 `docs/week4/bench_pg.py` 로 같은 방식(워밍업 1회 + 측정 5회, 순차 실행, 맨 위 노드 `actual time` 끝 값)으로 측정한다. 원본 결과는 `docs/week4/results/pg-after/` 에 남는다 (`summary.tsv`, `<ID>.plan.txt`, `<ID>.sql`).
  - SQL 은 앱(`local-pg` 프로필)을 띄워 같은 URL 을 호출하고 PostgreSQL statement 로그(`log_statement = all`)로 잡았다. MySQL 과 페이징만 다르다 (`limit 4950,50` → `offset 4950 rows fetch first 50 rows only`). `bench_pg.py` 는 이 문서의 MySQL SQL 을 그렇게 바꿔 실행한다. 케이스별 상세에 PostgreSQL SQL 을 함께 적었다.
  - 앱은 `brand_id`·offset·limit 을 바인딩 변수(`$1` ..)로 보내지만 측정은 값을 직접 넣어 했다. pgJDBC 는 같은 문장을 5번 넘게 실행하면 서버 측 prepared statement 로 바꾸므로, 그 뒤 PostgreSQL 이 generic plan 을 고르면 앱의 실행 계획은 여기 붙인 것과 다를 수 있다.
  - PostgreSQL 은 실행 계획 끝에 `Execution Time` 을 따로 찍는다. 표의 값은 MySQL 과 같은 기준(맨 위 노드)이라 JIT 컴파일 시간(전체 목록에서 약 70ms)만큼 `Execution Time` 보다 작다.

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

| ID | 브랜드 | 페이지 | 정렬 | 인덱스 적용 전 | 인덱스 적용 후 | 쿼리 변경 후 (MySQL, [8장](#8-쿼리-변경-후-검증)) | PostgreSQL (인덱스 적용) | PostgreSQL (쿼리 변경 후, [8장](#8-쿼리-변경-후-검증)) | PostgreSQL (부분 인덱스, [9장](#9-postgresql-부분-인덱스-적용-후)) |
|---|---|---|---|---|---|---|---|---|---|
| L01 | 전체 | 첫 페이지 | `latest` | 41,410.4 (40,286.0~43,414.0) | 65,350.6 (62,870.0~70,511.0) | [0.7 (0.7~0.8)](#q01) | 7,855.1 (7,237.1~8,464.0) | [227.2 (225.4~229.2)](#q01) | [0.2 (0.2~0.2)](#q01-부분-인덱스) |
| L02 | 전체 | 첫 페이지 | `price_asc` | 37,686.8 (37,358.0~38,224.0) | 60,067.0 (59,465.0~60,420.0) | [680.8 (676.0~684.0)](#q02) | 7,441.3 (7,146.8~7,809.0) | [185.4 (183.7~186.9)](#q02) | [0.2 (0.2~0.2)](#q02-부분-인덱스) |
| L03 | 전체 | 첫 페이지 | `likes_desc` | 37,608.6 (36,984.0~37,855.0) | 60,342.2 (59,502.0~61,038.0) | — (쿼리 그대로) | 7,264.1 (7,065.5~7,636.2) | — (쿼리 그대로) | — (쿼리 그대로) |
| L04 | 전체 | 딥 페이지 | `latest` | 41,904.6 (41,552.0~42,176.0) | 66,412.8 (65,336.0~67,741.0) | [1.1 (1.0~1.2)](#q04) | 7,556.0 (7,304.7~7,857.6) | [303.7 (301.5~306.9)](#q04) | [0.4 (0.4~0.5)](#q04-부분-인덱스) |
| L05 | 전체 | 딥 페이지 | `price_asc` | 38,652.4 (38,128.0~39,168.0) | 60,587.4 (60,004.0~61,452.0) | [683.8 (678.0~690.0)](#q05) | 7,370.5 (7,078.3~7,962.0) | [195.2 (193.7~198.3)](#q05) | [0.5 (0.5~0.6)](#q05-부분-인덱스) |
| L06 | 전체 | 딥 페이지 | `likes_desc` | 38,378.8 (37,783.0~38,842.0) | 60,413.8 (59,842.0~61,197.0) | — (쿼리 그대로) | 7,885.5 (7,481.9~8,478.5) | — (쿼리 그대로) | — (쿼리 그대로) |
| L07 | 1번 브랜드 | 첫 페이지 | `latest` | 5,605.4 (5,416.0~5,943.0) | 3,216.2 (3,129.0~3,477.0) | [0.6 (0.5~0.6)](#q07) | 1,529.5 (1,452.0~1,799.0) | [30.3 (27.9~32.5)](#q07) | [0.2 (0.2~0.2)](#q07-부분-인덱스) |
| L08 | 1번 브랜드 | 첫 페이지 | `price_asc` | 5,222.6 (5,088.0~5,281.0) | 2,960.6 (2,915.0~3,102.0) | [76.7 (75.5~77.4)](#q08) | 1,511.4 (1,432.2~1,660.5) | [22.5 (21.4~23.8)](#q08) | [0.3 (0.3~0.3)](#q08-부분-인덱스) |
| L09 | 1번 브랜드 | 첫 페이지 | `likes_desc` | 5,144.8 (5,063.0~5,229.0) | 2,953.0 (2,910.0~3,059.0) | — (쿼리 그대로) | 1,450.6 (1,426.0~1,518.9) | — (쿼리 그대로) | — (쿼리 그대로) |
| L10 | 1번 브랜드 | 딥 페이지 | `latest` | 5,595.2 (5,394.0~5,720.0) | 3,165.2 (3,117.0~3,267.0) | [1.3 (1.1~1.6)](#q10) | 1,467.6 (1,448.7~1,508.1) | [41.2 (38.9~44.8)](#q10) | [0.5 (0.4~0.5)](#q10-부분-인덱스) |
| L11 | 1번 브랜드 | 딥 페이지 | `price_asc` | 5,305.6 (5,231.0~5,440.0) | 2,975.0 (2,965.0~3,007.0) | [93.5 (91.7~96.5)](#q11) | 1,520.4 (1,454.9~1,623.6) | [25.6 (23.0~30.4)](#q11) | [0.6 (0.6~0.6)](#q11-부분-인덱스) |
| L12 | 1번 브랜드 | 딥 페이지 | `likes_desc` | 5,236.6 (5,163.0~5,423.0) | 2,982.0 (2,936.0~3,093.0) | — (쿼리 그대로) | 1,486.2 (1,442.7~1,572.2) | — (쿼리 그대로) | — (쿼리 그대로) |

`likes_desc` 는 쿼리를 바꾸지 않아 "쿼리 변경 후"·"부분 인덱스" 를 재지 않았다. MySQL "쿼리 변경 후" 는 데이터를 다시 만든 뒤 잰 값이라, 같은 데이터에서의 변경 전 값은 8-3 에 따로 적었다.

### 4-2. count 쿼리 실행 시간 (평균, ms)

| ID | 브랜드 | 인덱스 적용 전 | 인덱스 적용 후 | PostgreSQL (인덱스 적용) |
|---|---|---|---|---|
| C01 | 전체 | 968.6 (956.0~998.0) | 858.8 (854.0~867.0) | 125.0 (118.8~137.0) |
| C02 | 1번 브랜드 | 896.6 (876.0~963.0) | 98.1 (97.1~99.5) | 20.2 (19.3~21.1) |

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

PostgreSQL 에서는 페이징만 다르다.

```sql
select pm1_0.id,pm1_0.name,pm1_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from product pm1_0
join brand bm1_0 on bm1_0.id=pm1_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm1_0.id
where pm1_0.deleted_at is null
group by pm1_0.id,bm1_0.id
order by pm1_0.created_at desc,pm1_0.id desc
offset 0 rows fetch first 50 rows only
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 41,410.4 (40,286.0~43,414.0) |
| 인덱스 적용 후 | 65,350.6 (62,870.0~70,511.0) |
| PostgreSQL (인덱스 적용) | 7,855.1 (7,237.1~8,464.0) |
| 쿼리 변경 후 (MySQL, 데이터를 다시 만든 뒤. 같은 데이터의 변경 전은 [Q01](#q01)) | 0.7 (0.7~0.8) |
| PostgreSQL (쿼리 변경 후) | 227.2 (225.4~229.2) |
| PostgreSQL (부분 인덱스 적용 후, [9장](#9-postgresql-부분-인덱스-적용-후)) | 0.2 (0.2~0.2) |

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

**실행 계획 — PostgreSQL (인덱스 적용)**

```text
Limit  (cost=2274815.73..2274815.85 rows=50 width=67) (actual time=8198.977..8199.000 rows=50 loops=1)
  ->  Sort  (cost=2274815.73..2292637.75 rows=7128808 width=67) (actual time=8126.446..8126.464 rows=50 loops=1)
        Sort Key: pm1_0.created_at DESC, pm1_0.id DESC
        Sort Method: top-N heapsort  Memory: 32kB
        ->  GroupAggregate  (cost=1895425.69..2038001.85 rows=7128808 width=67) (actual time=6254.244..7765.777 rows=4749743 loops=1)
              Group Key: pm1_0.id, bm1_0.id
              ->  Sort  (cost=1895425.69..1913247.71 rows=7128808 width=67) (actual time=6253.885..6857.202 rows=10210933 loops=1)
                    Sort Key: pm1_0.id DESC, bm1_0.id
                    Sort Method: external merge  Disk: 795376kB
                    ->  Hash Join  (cost=207846.90..499193.94 rows=7128808 width=67) (actual time=932.554..3286.156 rows=10210933 loops=1)
                          Hash Cond: (pm1_0.brand_id = bm1_0.id)
                          ->  Hash Right Join  (cost=207815.40..480370.01 rows=7128808 width=54) (actual time=932.432..2664.572 rows=10210933 loops=1)
                                Hash Cond: (plm1_0.product_id = pm1_0.id)
                                ->  Seq Scan on product_like plm1_0  (cost=0.00..137757.27 rows=7514027 width=16) (actual time=0.094..460.215 rows=7514034 loops=1)
                                ->  Hash  (cost=106826.80..106826.80 rows=4743648 width=46) (actual time=930.776..930.778 rows=4749743 loops=1)
                                      Buckets: 65536  Batches: 128  Memory Usage: 3416kB
                                      ->  Seq Scan on product pm1_0  (cost=0.00..106826.80 rows=4743648 width=46) (actual time=0.071..534.641 rows=4749743 loops=1)
                                            Filter: (deleted_at IS NULL)
                                            Rows Removed by Filter: 250257
                          ->  Hash  (cost=19.00..19.00 rows=1000 width=21) (actual time=0.109..0.110 rows=1000 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 61kB
                                ->  Seq Scan on brand bm1_0  (cost=0.00..19.00 rows=1000 width=21) (actual time=0.010..0.067 rows=1000 loops=1)
Planning Time: 0.664 ms
JIT:
  Functions: 27
  Options: Inlining true, Optimization true, Expressions true, Deforming true
  Timing: Generation 0.512 ms, Inlining 26.104 ms, Optimization 24.189 ms, Emission 22.503 ms, Total 73.308 ms
Execution Time: 8262.430 ms
```

**실행 계획 — MySQL 쿼리 변경 후** (SQL 은 [Q01](#q01))

```text
-> Sort: pm2_0.created_at DESC, pm2_0.id DESC  (actual time=0.683..0.684 rows=50 loops=1)
    -> Table scan on <temporary>  (actual time=0.668..0.671 rows=50 loops=1)
        -> Aggregate using temporary table  (actual time=0.668..0.668 rows=50 loops=1)
            -> Nested loop left join  (cost=232793 rows=206) (actual time=0.507..0.624 rows=107 loops=1)
                -> Nested loop inner join  (cost=232759 rows=50) (actual time=0.502..0.564 rows=50 loops=1)
                    -> Nested loop inner join  (cost=232742 rows=50) (actual time=0.501..0.533 rows=50 loops=1)
                        -> Table scan on pg1_0  (cost=232688..232691 rows=50) (actual time=0.497..0.499 rows=50 loops=1)
                            -> Materialize CTE page  (cost=232688..232688 rows=50) (actual time=0.496..0.496 rows=50 loops=1)
                                -> Limit: 50 row(s)  (cost=232683 rows=50) (actual time=0.482..0.487 rows=50 loops=1)
                                    -> Filter: (pm1_0.deleted_at is null)  (cost=232683 rows=2.3e+6) (actual time=0.482..0.485 rows=50 loops=1)
                                        -> Covering index lookup on pm1_0 using ix_product_latest (deleted_at=NULL) (reverse)  (cost=232683 rows=2.3e+6) (actual time=0.481..0.484 rows=50 loops=1)
                        -> Single-row index lookup on pm2_0 using PRIMARY (id=pg1_0.id)  (cost=0.913 rows=1) (actual time=575e-6..597e-6 rows=1 loops=50)
                    -> Single-row index lookup on bm1_0 using PRIMARY (id=pm2_0.brand_id)  (cost=0.252 rows=1) (actual time=515e-6..529e-6 rows=1 loops=50)
                -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pg1_0.id)  (cost=0.26 rows=4.11) (actual time=946e-6..0.00108 rows=1.52 loops=50)
```

**실행 계획 — PostgreSQL 쿼리 변경 후** (SQL 은 [Q01](#q01))

```text
Sort  (cost=144937.41..144937.60 rows=75 width=67) (actual time=221.294..225.381 rows=50 loops=1)
  Sort Key: pm2_0.created_at DESC, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=144933.58..144935.08 rows=75 width=67) (actual time=221.261..225.360 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=144933.58..144933.77 rows=75 width=67) (actual time=221.253..225.341 rows=73 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 35kB
              ->  Nested Loop Left Join  (cost=144319.99..144931.24 rows=75 width=67) (actual time=221.106..225.311 rows=73 loops=1)
                    ->  Nested Loop  (cost=144319.55..144762.31 rows=50 width=59) (actual time=221.100..225.270 rows=50 loops=1)
                          ->  Nested Loop  (cost=144319.28..144747.68 rows=50 width=46) (actual time=221.093..225.226 rows=50 loops=1)
                                ->  Limit  (cost=144318.85..144324.68 rows=50 width=16) (actual time=221.065..225.161 rows=50 loops=1)
                                      ->  Gather Merge  (cost=144318.85..605539.05 rows=3953040 width=16) (actual time=215.598..219.687 rows=50 loops=1)
                                            Workers Planned: 2
                                            Workers Launched: 2
                                            ->  Sort  (cost=143318.82..148260.12 rows=1976520 width=16) (actual time=210.306..210.307 rows=42 loops=3)
                                                  Sort Key: pm1_0.created_at DESC, pm1_0.id DESC
                                                  Sort Method: top-N heapsort  Memory: 29kB
                                                  Worker 0:  Sort Method: top-N heapsort  Memory: 29kB
                                                  Worker 1:  Sort Method: top-N heapsort  Memory: 29kB
                                                  ->  Parallel Seq Scan on product pm1_0  (cost=0.00..77660.25 rows=1976520 width=16) (actual time=1.011..79.463 rows=1583248 loops=3)
                                                        Filter: (deleted_at IS NULL)
                                                        Rows Removed by Filter: 83419
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=46) (actual time=0.001..0.001 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.001..0.001 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.001..0.001 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.441 ms
JIT:
  Functions: 34
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.633 ms, Inlining 0.000 ms, Optimization 0.439 ms, Emission 8.051 ms, Total 9.123 ms
Execution Time: 232.224 ms
```

**실행 계획 — PostgreSQL 부분 인덱스 적용 후** (SQL 은 [Q01](#q01), 인덱스는 [9-1](#9-1-변경-내용))

```text
Sort  (cost=614.68..614.87 rows=75 width=67) (actual time=0.158..0.160 rows=50 loops=1)
  Sort Key: pm2_0.created_at DESC, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=610.85..612.35 rows=75 width=67) (actual time=0.131..0.144 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=610.85..611.04 rows=75 width=67) (actual time=0.128..0.130 rows=73 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 35kB
              ->  Nested Loop Left Join  (cost=1.57..608.51 rows=75 width=67) (actual time=0.022..0.117 rows=73 loops=1)
                    ->  Nested Loop  (cost=1.14..439.58 rows=50 width=59) (actual time=0.019..0.085 rows=50 loops=1)
                          ->  Nested Loop  (cost=0.86..424.95 rows=50 width=46) (actual time=0.013..0.048 rows=50 loops=1)
                                ->  Limit  (cost=0.43..1.95 rows=50 width=16) (actual time=0.007..0.012 rows=50 loops=1)
                                      ->  Index Only Scan Backward using ix_product_latest_active on product pm1_0  (cost=0.43..144306.33 rows=4749460 width=16) (actual time=0.007..0.009 rows=50 loops=1)
                                            Heap Fetches: 0
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=46) (actual time=0.001..0.001 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.001..0.001 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.000..0.000 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.515 ms
Execution Time: 0.200 ms
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

PostgreSQL 에서는 페이징만 다르다.

```sql
select pm1_0.id,pm1_0.name,pm1_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from product pm1_0
join brand bm1_0 on bm1_0.id=pm1_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm1_0.id
where pm1_0.deleted_at is null
group by pm1_0.id,bm1_0.id
order by pm1_0.price,pm1_0.id desc
offset 0 rows fetch first 50 rows only
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 37,686.8 (37,358.0~38,224.0) |
| 인덱스 적용 후 | 60,067.0 (59,465.0~60,420.0) |
| PostgreSQL (인덱스 적용) | 7,441.3 (7,146.8~7,809.0) |
| 쿼리 변경 후 (MySQL, 데이터를 다시 만든 뒤. 같은 데이터의 변경 전은 [Q02](#q02)) | 680.8 (676.0~684.0) |
| PostgreSQL (쿼리 변경 후) | 185.4 (183.7~186.9) |
| PostgreSQL (부분 인덱스 적용 후, [9장](#9-postgresql-부분-인덱스-적용-후)) | 0.2 (0.2~0.2) |

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

**실행 계획 — PostgreSQL (인덱스 적용)**

```text
Limit  (cost=2216815.73..2216815.85 rows=50 width=59) (actual time=7546.079..7546.096 rows=50 loops=1)
  ->  Sort  (cost=2216815.73..2234637.75 rows=7128808 width=59) (actual time=7478.955..7478.969 rows=50 loops=1)
        Sort Key: pm1_0.price, pm1_0.id DESC
        Sort Method: top-N heapsort  Memory: 36kB
        ->  GroupAggregate  (cost=1837425.69..1980001.85 rows=7128808 width=59) (actual time=5818.411..7192.288 rows=4749743 loops=1)
              Group Key: pm1_0.id, bm1_0.id
              ->  Sort  (cost=1837425.69..1855247.71 rows=7128808 width=59) (actual time=5818.171..6369.820 rows=10210933 loops=1)
                    Sort Key: pm1_0.id DESC, bm1_0.id
                    Sort Method: external merge  Disk: 707456kB
                    ->  Hash Join  (cost=203213.90..489927.94 rows=7128808 width=59) (actual time=847.973..3143.251 rows=10210933 loops=1)
                          Hash Cond: (pm1_0.brand_id = bm1_0.id)
                          ->  Hash Right Join  (cost=203182.40..471104.01 rows=7128808 width=46) (actual time=847.841..2529.435 rows=10210933 loops=1)
                                Hash Cond: (plm1_0.product_id = pm1_0.id)
                                ->  Seq Scan on product_like plm1_0  (cost=0.00..137757.27 rows=7514027 width=16) (actual time=0.106..423.350 rows=7514034 loops=1)
                                ->  Hash  (cost=106826.80..106826.80 rows=4743648 width=38) (actual time=847.162..847.166 rows=4749743 loops=1)
                                      Buckets: 65536  Batches: 128  Memory Usage: 3127kB
                                      ->  Seq Scan on product pm1_0  (cost=0.00..106826.80 rows=4743648 width=38) (actual time=0.054..458.451 rows=4749743 loops=1)
                                            Filter: (deleted_at IS NULL)
                                            Rows Removed by Filter: 250257
                          ->  Hash  (cost=19.00..19.00 rows=1000 width=21) (actual time=0.124..0.125 rows=1000 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 61kB
                                ->  Seq Scan on brand bm1_0  (cost=0.00..19.00 rows=1000 width=21) (actual time=0.007..0.081 rows=1000 loops=1)
Planning Time: 0.475 ms
JIT:
  Functions: 27
  Options: Inlining true, Optimization true, Expressions true, Deforming true
  Timing: Generation 0.345 ms, Inlining 24.129 ms, Optimization 22.246 ms, Emission 20.959 ms, Total 67.679 ms
Execution Time: 7598.005 ms
```

**실행 계획 — MySQL 쿼리 변경 후** (SQL 은 [Q02](#q02))

```text
-> Sort: pm2_0.price, pm2_0.id DESC  (actual time=684..684 rows=50 loops=1)
    -> Table scan on <temporary>  (actual time=684..684 rows=50 loops=1)
        -> Aggregate using temporary table  (actual time=684..684 rows=50 loops=1)
            -> Nested loop left join  (cost=233316 rows=206) (actual time=684..684 rows=67 loops=1)
                -> Nested loop inner join  (cost=233282 rows=50) (actual time=684..684 rows=50 loops=1)
                    -> Nested loop inner join  (cost=233265 rows=50) (actual time=684..684 rows=50 loops=1)
                        -> Table scan on pg1_0  (cost=233211..233214 rows=50) (actual time=684..684 rows=50 loops=1)
                            -> Materialize CTE page  (cost=233211..233211 rows=50) (actual time=684..684 rows=50 loops=1)
                                -> Limit: 50 row(s)  (cost=233206 rows=50) (actual time=684..684 rows=50 loops=1)
                                    -> Sort: pm1_0.price, pm1_0.id DESC, limit input to 50 row(s) per chunk  (cost=233206 rows=2.3e+6) (actual time=684..684 rows=50 loops=1)
                                        -> Filter: (pm1_0.deleted_at is null)  (cost=233206 rows=2.3e+6) (actual time=0.0443..495 rows=4.75e+6 loops=1)
                                            -> Index lookup on pm1_0 using ix_product_price (deleted_at=NULL)  (cost=233206 rows=2.3e+6) (actual time=0.044..352 rows=4.75e+6 loops=1)
                        -> Single-row index lookup on pm2_0 using PRIMARY (id=pg1_0.id)  (cost=0.913 rows=1) (actual time=0.00325..0.00327 rows=1 loops=50)
                    -> Single-row index lookup on bm1_0 using PRIMARY (id=pm2_0.brand_id)  (cost=0.252 rows=1) (actual time=687e-6..704e-6 rows=1 loops=50)
                -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pg1_0.id)  (cost=0.26 rows=4.11) (actual time=983e-6..0.00106 rows=0.72 loops=50)
```

**실행 계획 — PostgreSQL 쿼리 변경 후** (SQL 은 [Q02](#q02))

```text
Sort  (cost=144937.41..144937.60 rows=75 width=59) (actual time=180.992..185.460 rows=50 loops=1)
  Sort Key: pm2_0.price, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=144933.58..144935.08 rows=75 width=59) (actual time=180.960..185.440 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=144933.58..144933.77 rows=75 width=59) (actual time=180.952..185.420 rows=79 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 36kB
              ->  Nested Loop Left Join  (cost=144319.99..144931.24 rows=75 width=59) (actual time=180.737..185.393 rows=79 loops=1)
                    ->  Nested Loop  (cost=144319.55..144762.31 rows=50 width=51) (actual time=180.721..185.294 rows=50 loops=1)
                          ->  Nested Loop  (cost=144319.28..144747.68 rows=50 width=38) (actual time=180.711..185.243 rows=50 loops=1)
                                ->  Limit  (cost=144318.85..144324.68 rows=50 width=16) (actual time=180.663..185.134 rows=50 loops=1)
                                      ->  Gather Merge  (cost=144318.85..605539.05 rows=3953040 width=16) (actual time=175.180..179.649 rows=50 loops=1)
                                            Workers Planned: 2
                                            Workers Launched: 2
                                            ->  Sort  (cost=143318.82..148260.12 rows=1976520 width=16) (actual time=169.676..169.678 rows=38 loops=3)
                                                  Sort Key: pm1_0.price, pm1_0.id DESC
                                                  Sort Method: top-N heapsort  Memory: 29kB
                                                  Worker 0:  Sort Method: top-N heapsort  Memory: 28kB
                                                  Worker 1:  Sort Method: top-N heapsort  Memory: 28kB
                                                  ->  Parallel Seq Scan on product pm1_0  (cost=0.00..77660.25 rows=1976520 width=16) (actual time=1.081..102.239 rows=1583248 loops=3)
                                                        Filter: (deleted_at IS NULL)
                                                        Rows Removed by Filter: 83419
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=38) (actual time=0.001..0.001 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.001..0.001 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.002..0.002 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.456 ms
JIT:
  Functions: 34
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.729 ms, Inlining 0.000 ms, Optimization 0.512 ms, Emission 8.213 ms, Total 9.454 ms
Execution Time: 192.472 ms
```

**실행 계획 — PostgreSQL 부분 인덱스 적용 후** (SQL 은 [Q02](#q02), 인덱스는 [9-1](#9-1-변경-내용))

```text
Sort  (cost=614.69..614.87 rows=75 width=59) (actual time=0.212..0.214 rows=50 loops=1)
  Sort Key: pm2_0.price, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=610.85..612.35 rows=75 width=59) (actual time=0.191..0.202 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=610.85..611.04 rows=75 width=59) (actual time=0.189..0.191 rows=79 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 36kB
              ->  Nested Loop Left Join  (cost=1.57..608.51 rows=75 width=59) (actual time=0.022..0.178 rows=79 loops=1)
                    ->  Nested Loop  (cost=1.14..439.58 rows=50 width=51) (actual time=0.020..0.127 rows=50 loops=1)
                          ->  Nested Loop  (cost=0.86..424.95 rows=50 width=38) (actual time=0.017..0.098 rows=50 loops=1)
                                ->  Limit  (cost=0.43..1.95 rows=50 width=16) (actual time=0.008..0.012 rows=50 loops=1)
                                      ->  Index Only Scan using ix_product_price_active on product pm1_0  (cost=0.43..144406.33 rows=4749460 width=16) (actual time=0.008..0.010 rows=50 loops=1)
                                            Heap Fetches: 0
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=38) (actual time=0.002..0.002 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.000..0.000 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.001..0.001 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.479 ms
Execution Time: 0.248 ms
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

PostgreSQL 에서는 페이징만 다르다.

```sql
select pm1_0.id,pm1_0.name,pm1_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from product pm1_0
join brand bm1_0 on bm1_0.id=pm1_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm1_0.id
where pm1_0.deleted_at is null
group by pm1_0.id,bm1_0.id
order by count(plm1_0.id) desc,pm1_0.id desc
offset 0 rows fetch first 50 rows only
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 37,608.6 (36,984.0~37,855.0) |
| 인덱스 적용 후 | 60,342.2 (59,502.0~61,038.0) |
| PostgreSQL (인덱스 적용) | 7,264.1 (7,065.5~7,636.2) |

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

**실행 계획 — PostgreSQL (인덱스 적용)**

```text
Limit  (cost=2216815.73..2216815.85 rows=50 width=59) (actual time=7124.387..7124.412 rows=50 loops=1)
  ->  Sort  (cost=2216815.73..2234637.75 rows=7128808 width=59) (actual time=7051.068..7051.089 rows=50 loops=1)
        Sort Key: (count(plm1_0.id)) DESC, pm1_0.id DESC
        Sort Method: top-N heapsort  Memory: 34kB
        ->  GroupAggregate  (cost=1837425.69..1980001.85 rows=7128808 width=59) (actual time=5162.110..6721.074 rows=4749743 loops=1)
              Group Key: pm1_0.id, bm1_0.id
              ->  Sort  (cost=1837425.69..1855247.71 rows=7128808 width=59) (actual time=5162.086..5854.630 rows=10210933 loops=1)
                    Sort Key: pm1_0.id DESC, bm1_0.id
                    Sort Method: external merge  Disk: 707456kB
                    ->  Hash Join  (cost=203213.90..489927.94 rows=7128808 width=59) (actual time=574.631..2637.152 rows=10210933 loops=1)
                          Hash Cond: (pm1_0.brand_id = bm1_0.id)
                          ->  Hash Right Join  (cost=203182.40..471104.01 rows=7128808 width=46) (actual time=574.531..2022.443 rows=10210933 loops=1)
                                Hash Cond: (plm1_0.product_id = pm1_0.id)
                                ->  Seq Scan on product_like plm1_0  (cost=0.00..137757.27 rows=7514027 width=16) (actual time=0.025..298.631 rows=7514034 loops=1)
                                ->  Hash  (cost=106826.80..106826.80 rows=4743648 width=38) (actual time=574.138..574.140 rows=4749743 loops=1)
                                      Buckets: 65536  Batches: 128  Memory Usage: 3127kB
                                      ->  Seq Scan on product pm1_0  (cost=0.00..106826.80 rows=4743648 width=38) (actual time=0.050..226.306 rows=4749743 loops=1)
                                            Filter: (deleted_at IS NULL)
                                            Rows Removed by Filter: 250257
                          ->  Hash  (cost=19.00..19.00 rows=1000 width=21) (actual time=0.094..0.096 rows=1000 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 61kB
                                ->  Seq Scan on brand bm1_0  (cost=0.00..19.00 rows=1000 width=21) (actual time=0.014..0.053 rows=1000 loops=1)
Planning Time: 0.441 ms
JIT:
  Functions: 27
  Options: Inlining true, Optimization true, Expressions true, Deforming true
  Timing: Generation 0.427 ms, Inlining 24.173 ms, Optimization 27.227 ms, Emission 21.940 ms, Total 73.767 ms
Execution Time: 7172.131 ms
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

PostgreSQL 에서는 페이징만 다르다.

```sql
select pm1_0.id,pm1_0.name,pm1_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from product pm1_0
join brand bm1_0 on bm1_0.id=pm1_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm1_0.id
where pm1_0.deleted_at is null
group by pm1_0.id,bm1_0.id
order by pm1_0.created_at desc,pm1_0.id desc
offset 4950 rows fetch first 50 rows only
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 41,904.6 (41,552.0~42,176.0) |
| 인덱스 적용 후 | 66,412.8 (65,336.0~67,741.0) |
| PostgreSQL (인덱스 적용) | 7,556.0 (7,304.7~7,857.6) |
| 쿼리 변경 후 (MySQL, 데이터를 다시 만든 뒤. 같은 데이터의 변경 전은 [Q04](#q04)) | 1.1 (1.0~1.2) |
| PostgreSQL (쿼리 변경 후) | 303.7 (301.5~306.9) |
| PostgreSQL (부분 인덱스 적용 후, [9장](#9-postgresql-부분-인덱스-적용-후)) | 0.4 (0.4~0.5) |

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

**실행 계획 — PostgreSQL (인덱스 적용)**

```text
Limit  (cost=2511641.98..2511642.10 rows=50 width=67) (actual time=7857.518..7857.567 rows=50 loops=1)
  ->  Sort  (cost=2511629.60..2529451.62 rows=7128808 width=67) (actual time=7786.949..7787.110 rows=5000 loops=1)
        Sort Key: pm1_0.created_at DESC, pm1_0.id DESC
        Sort Method: top-N heapsort  Memory: 1088kB
        ->  GroupAggregate  (cost=1895425.69..2038001.85 rows=7128808 width=67) (actual time=5811.610..7444.754 rows=4749743 loops=1)
              Group Key: pm1_0.id, bm1_0.id
              ->  Sort  (cost=1895425.69..1913247.71 rows=7128808 width=67) (actual time=5811.274..6566.051 rows=10210933 loops=1)
                    Sort Key: pm1_0.id DESC, bm1_0.id
                    Sort Method: external merge  Disk: 795376kB
                    ->  Hash Join  (cost=207846.90..499193.94 rows=7128808 width=67) (actual time=654.829..2783.991 rows=10210933 loops=1)
                          Hash Cond: (pm1_0.brand_id = bm1_0.id)
                          ->  Hash Right Join  (cost=207815.40..480370.01 rows=7128808 width=54) (actual time=654.720..2158.496 rows=10210933 loops=1)
                                Hash Cond: (plm1_0.product_id = pm1_0.id)
                                ->  Seq Scan on product_like plm1_0  (cost=0.00..137757.27 rows=7514027 width=16) (actual time=0.035..313.828 rows=7514034 loops=1)
                                ->  Hash  (cost=106826.80..106826.80 rows=4743648 width=46) (actual time=654.256..654.261 rows=4749743 loops=1)
                                      Buckets: 65536  Batches: 128  Memory Usage: 3416kB
                                      ->  Seq Scan on product pm1_0  (cost=0.00..106826.80 rows=4743648 width=46) (actual time=0.055..251.608 rows=4749743 loops=1)
                                            Filter: (deleted_at IS NULL)
                                            Rows Removed by Filter: 250257
                          ->  Hash  (cost=19.00..19.00 rows=1000 width=21) (actual time=0.103..0.112 rows=1000 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 61kB
                                ->  Seq Scan on brand bm1_0  (cost=0.00..19.00 rows=1000 width=21) (actual time=0.009..0.060 rows=1000 loops=1)
Planning Time: 0.361 ms
JIT:
  Functions: 27
  Options: Inlining true, Optimization true, Expressions true, Deforming true
  Timing: Generation 0.384 ms, Inlining 25.225 ms, Optimization 23.311 ms, Emission 22.086 ms, Total 71.006 ms
Execution Time: 7928.129 ms
```

**실행 계획 — MySQL 쿼리 변경 후** (SQL 은 [Q04](#q04))

```text
-> Sort: pm2_0.created_at DESC, pm2_0.id DESC  (actual time=1.24..1.24 rows=50 loops=1)
    -> Table scan on <temporary>  (actual time=1.23..1.23 rows=50 loops=1)
        -> Aggregate using temporary table  (actual time=1.23..1.23 rows=50 loops=1)
            -> Nested loop left join  (cost=239783 rows=206) (actual time=1.08..1.19 rows=68 loops=1)
                -> Nested loop inner join  (cost=238505 rows=50) (actual time=1.08..1.15 rows=50 loops=1)
                    -> Nested loop inner join  (cost=237250 rows=50) (actual time=1.07..1.11 rows=50 loops=1)
                        -> Table scan on pg1_0  (cost=232688..232691 rows=50) (actual time=1.07..1.07 rows=50 loops=1)
                            -> Materialize CTE page  (cost=232688..232688 rows=50) (actual time=1.07..1.07 rows=50 loops=1)
                                -> Limit/Offset: 50/4950 row(s)  (cost=232683 rows=50) (actual time=1.05..1.06 rows=50 loops=1)
                                    -> Filter: (pm1_0.deleted_at is null)  (cost=232683 rows=2.3e+6) (actual time=0.656..0.967 rows=5000 loops=1)
                                        -> Covering index lookup on pm1_0 using ix_product_latest (deleted_at=NULL) (reverse)  (cost=232683 rows=2.3e+6) (actual time=0.655..0.817 rows=5000 loops=1)
                        -> Single-row index lookup on pm2_0 using PRIMARY (id=pg1_0.id)  (cost=0.911 rows=1) (actual time=737e-6..754e-6 rows=1 loops=50)
                    -> Single-row index lookup on bm1_0 using PRIMARY (id=pm2_0.brand_id)  (cost=0.25 rows=1) (actual time=587e-6..600e-6 rows=1 loops=50)
                -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pg1_0.id)  (cost=0.252 rows=4.11) (actual time=698e-6..770e-6 rows=0.72 loops=50)
```

**실행 계획 — PostgreSQL 쿼리 변경 후** (SQL 은 [Q04](#q04))

```text
Sort  (cost=211173.53..211173.71 rows=75 width=67) (actual time=297.014..301.466 rows=50 loops=1)
  Sort Key: pm2_0.created_at DESC, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=211169.69..211171.19 rows=75 width=67) (actual time=296.995..301.456 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=211169.69..211169.88 rows=75 width=67) (actual time=296.989..301.441 rows=64 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 34kB
              ->  Nested Loop Left Join  (cost=210556.10..211167.36 rows=75 width=67) (actual time=296.883..301.429 rows=64 loops=1)
                    ->  Nested Loop  (cost=210555.67..210998.42 rows=50 width=59) (actual time=296.873..301.391 rows=50 loops=1)
                          ->  Nested Loop  (cost=210555.39..210983.79 rows=50 width=46) (actual time=296.867..301.354 rows=50 loops=1)
                                ->  Limit  (cost=210554.96..210560.79 rows=50 width=16) (actual time=296.836..301.291 rows=50 loops=1)
                                      ->  Gather Merge  (cost=209977.42..671197.62 rows=3953040 width=16) (actual time=291.008..295.863 rows=5000 loops=1)
                                            Workers Planned: 2
                                            Workers Launched: 2
                                            ->  Sort  (cost=208977.40..213918.70 rows=1976520 width=16) (actual time=285.872..285.932 rows=2729 loops=3)
                                                  Sort Key: pm1_0.created_at DESC, pm1_0.id DESC
                                                  Sort Method: top-N heapsort  Memory: 853kB
                                                  Worker 0:  Sort Method: top-N heapsort  Memory: 853kB
                                                  Worker 1:  Sort Method: top-N heapsort  Memory: 853kB
                                                  ->  Parallel Seq Scan on product pm1_0  (cost=0.00..77660.25 rows=1976520 width=16) (actual time=0.915..79.782 rows=1583248 loops=3)
                                                        Filter: (deleted_at IS NULL)
                                                        Rows Removed by Filter: 83419
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=46) (actual time=0.001..0.001 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.001..0.001 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.001..0.001 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.420 ms
JIT:
  Functions: 34
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.519 ms, Inlining 0.000 ms, Optimization 0.383 ms, Emission 7.694 ms, Total 8.596 ms
Execution Time: 308.099 ms
```

**실행 계획 — PostgreSQL 부분 인덱스 적용 후** (SQL 은 [Q04](#q04), 인덱스는 [9-1](#9-1-변경-내용))

```text
Sort  (cost=765.08..765.27 rows=75 width=67) (actual time=0.440..0.441 rows=50 loops=1)
  Sort Key: pm2_0.created_at DESC, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=761.25..762.75 rows=75 width=67) (actual time=0.413..0.423 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=761.25..761.44 rows=75 width=67) (actual time=0.410..0.412 rows=64 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 34kB
              ->  Nested Loop Left Join  (cost=151.97..758.91 rows=75 width=67) (actual time=0.313..0.401 rows=64 loops=1)
                    ->  Nested Loop  (cost=151.54..589.98 rows=50 width=59) (actual time=0.308..0.371 rows=50 loops=1)
                          ->  Nested Loop  (cost=151.26..575.35 rows=50 width=46) (actual time=0.304..0.335 rows=50 loops=1)
                                ->  Limit  (cost=150.83..152.35 rows=50 width=16) (actual time=0.291..0.295 rows=50 loops=1)
                                      ->  Index Only Scan Backward using ix_product_latest_active on product pm1_0  (cost=0.43..144306.33 rows=4749460 width=16) (actual time=0.007..0.217 rows=5000 loops=1)
                                            Heap Fetches: 0
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=46) (actual time=0.001..0.001 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.001..0.001 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.000..0.000 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.531 ms
Execution Time: 0.482 ms
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

PostgreSQL 에서는 페이징만 다르다.

```sql
select pm1_0.id,pm1_0.name,pm1_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from product pm1_0
join brand bm1_0 on bm1_0.id=pm1_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm1_0.id
where pm1_0.deleted_at is null
group by pm1_0.id,bm1_0.id
order by pm1_0.price,pm1_0.id desc
offset 4950 rows fetch first 50 rows only
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 38,652.4 (38,128.0~39,168.0) |
| 인덱스 적용 후 | 60,587.4 (60,004.0~61,452.0) |
| PostgreSQL (인덱스 적용) | 7,370.5 (7,078.3~7,962.0) |
| 쿼리 변경 후 (MySQL, 데이터를 다시 만든 뒤. 같은 데이터의 변경 전은 [Q05](#q05)) | 683.8 (678.0~690.0) |
| PostgreSQL (쿼리 변경 후) | 195.2 (193.7~198.3) |
| PostgreSQL (부분 인덱스 적용 후, [9장](#9-postgresql-부분-인덱스-적용-후)) | 0.5 (0.5~0.6) |

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

**실행 계획 — PostgreSQL (인덱스 적용)**

```text
Limit  (cost=2453641.98..2453642.10 rows=50 width=59) (actual time=7235.318..7235.323 rows=50 loops=1)
  ->  Sort  (cost=2453629.60..2471451.62 rows=7128808 width=59) (actual time=7164.642..7164.759 rows=5000 loops=1)
        Sort Key: pm1_0.price, pm1_0.id DESC
        Sort Method: top-N heapsort  Memory: 1572kB
        ->  GroupAggregate  (cost=1837425.69..1980001.85 rows=7128808 width=59) (actual time=5417.310..6864.477 rows=4749743 loops=1)
              Group Key: pm1_0.id, bm1_0.id
              ->  Sort  (cost=1837425.69..1855247.71 rows=7128808 width=59) (actual time=5417.263..5991.535 rows=10210933 loops=1)
                    Sort Key: pm1_0.id DESC, bm1_0.id
                    Sort Method: external merge  Disk: 707456kB
                    ->  Hash Join  (cost=203213.90..489927.94 rows=7128808 width=59) (actual time=680.176..2786.724 rows=10210933 loops=1)
                          Hash Cond: (pm1_0.brand_id = bm1_0.id)
                          ->  Hash Right Join  (cost=203182.40..471104.01 rows=7128808 width=46) (actual time=680.071..2168.643 rows=10210933 loops=1)
                                Hash Cond: (plm1_0.product_id = pm1_0.id)
                                ->  Seq Scan on product_like plm1_0  (cost=0.00..137757.27 rows=7514027 width=16) (actual time=0.031..313.571 rows=7514034 loops=1)
                                ->  Hash  (cost=106826.80..106826.80 rows=4743648 width=38) (actual time=679.642..679.642 rows=4749743 loops=1)
                                      Buckets: 65536  Batches: 128  Memory Usage: 3127kB
                                      ->  Seq Scan on product pm1_0  (cost=0.00..106826.80 rows=4743648 width=38) (actual time=0.071..280.892 rows=4749743 loops=1)
                                            Filter: (deleted_at IS NULL)
                                            Rows Removed by Filter: 250257
                          ->  Hash  (cost=19.00..19.00 rows=1000 width=21) (actual time=0.099..0.100 rows=1000 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 61kB
                                ->  Seq Scan on brand bm1_0  (cost=0.00..19.00 rows=1000 width=21) (actual time=0.008..0.056 rows=1000 loops=1)
Planning Time: 0.378 ms
JIT:
  Functions: 27
  Options: Inlining true, Optimization true, Expressions true, Deforming true
  Timing: Generation 0.409 ms, Inlining 25.273 ms, Optimization 23.427 ms, Emission 21.843 ms, Total 70.952 ms
Execution Time: 7286.980 ms
```

**실행 계획 — MySQL 쿼리 변경 후** (SQL 은 [Q05](#q05))

```text
-> Sort: pm2_0.price, pm2_0.id DESC  (actual time=678..678 rows=50 loops=1)
    -> Table scan on <temporary>  (actual time=678..678 rows=50 loops=1)
        -> Aggregate using temporary table  (actual time=678..678 rows=50 loops=1)
            -> Nested loop left join  (cost=240306 rows=206) (actual time=678..678 rows=335 loops=1)
                -> Nested loop inner join  (cost=239028 rows=50) (actual time=678..678 rows=50 loops=1)
                    -> Nested loop inner join  (cost=237773 rows=50) (actual time=678..678 rows=50 loops=1)
                        -> Table scan on pg1_0  (cost=233211..233214 rows=50) (actual time=678..678 rows=50 loops=1)
                            -> Materialize CTE page  (cost=233211..233211 rows=50) (actual time=678..678 rows=50 loops=1)
                                -> Limit/Offset: 50/4950 row(s)  (cost=233206 rows=50) (actual time=678..678 rows=50 loops=1)
                                    -> Sort: pm1_0.price, pm1_0.id DESC, limit input to 5000 row(s) per chunk  (cost=233206 rows=2.3e+6) (actual time=678..678 rows=5000 loops=1)
                                        -> Filter: (pm1_0.deleted_at is null)  (cost=233206 rows=2.3e+6) (actual time=0.0311..491 rows=4.75e+6 loops=1)
                                            -> Index lookup on pm1_0 using ix_product_price (deleted_at=NULL)  (cost=233206 rows=2.3e+6) (actual time=0.0305..350 rows=4.75e+6 loops=1)
                        -> Single-row index lookup on pm2_0 using PRIMARY (id=pg1_0.id)  (cost=0.911 rows=1) (actual time=0.0028..0.00281 rows=1 loops=50)
                    -> Single-row index lookup on bm1_0 using PRIMARY (id=pm2_0.brand_id)  (cost=0.25 rows=1) (actual time=636e-6..647e-6 rows=1 loops=50)
                -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pg1_0.id)  (cost=0.252 rows=4.11) (actual time=0.00102..0.00149 rows=6.12 loops=50)
```

**실행 계획 — PostgreSQL 쿼리 변경 후** (SQL 은 [Q05](#q05))

```text
Sort  (cost=211173.53..211173.71 rows=75 width=59) (actual time=191.703..196.006 rows=50 loops=1)
  Sort Key: pm2_0.price, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=211169.69..211171.19 rows=75 width=59) (actual time=191.668..195.981 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=211169.69..211169.88 rows=75 width=59) (actual time=191.662..195.965 rows=80 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 36kB
              ->  Nested Loop Left Join  (cost=210556.10..211167.36 rows=75 width=59) (actual time=191.470..195.951 rows=80 loops=1)
                    ->  Nested Loop  (cost=210555.67..210998.42 rows=50 width=51) (actual time=191.463..195.886 rows=50 loops=1)
                          ->  Nested Loop  (cost=210555.39..210983.79 rows=50 width=38) (actual time=191.457..195.840 rows=50 loops=1)
                                ->  Limit  (cost=210554.96..210560.79 rows=50 width=16) (actual time=191.422..195.733 rows=50 loops=1)
                                      ->  Gather Merge  (cost=209977.42..671197.62 rows=3953040 width=16) (actual time=185.965..190.713 rows=5000 loops=1)
                                            Workers Planned: 2
                                            Workers Launched: 2
                                            ->  Sort  (cost=208977.40..213918.70 rows=1976520 width=16) (actual time=180.641..180.717 rows=2622 loops=3)
                                                  Sort Key: pm1_0.price, pm1_0.id DESC
                                                  Sort Method: top-N heapsort  Memory: 780kB
                                                  Worker 0:  Sort Method: top-N heapsort  Memory: 780kB
                                                  Worker 1:  Sort Method: top-N heapsort  Memory: 780kB
                                                  ->  Parallel Seq Scan on product pm1_0  (cost=0.00..77660.25 rows=1976520 width=16) (actual time=1.056..102.235 rows=1583248 loops=3)
                                                        Filter: (deleted_at IS NULL)
                                                        Rows Removed by Filter: 83419
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=38) (actual time=0.002..0.002 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.001..0.001 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.001..0.001 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.426 ms
JIT:
  Functions: 34
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.566 ms, Inlining 0.000 ms, Optimization 0.416 ms, Emission 7.677 ms, Total 8.659 ms
Execution Time: 202.606 ms
```

**실행 계획 — PostgreSQL 부분 인덱스 적용 후** (SQL 은 [Q05](#q05), 인덱스는 [9-1](#9-1-변경-내용))

```text
Sort  (cost=765.19..765.38 rows=75 width=59) (actual time=0.513..0.515 rows=50 loops=1)
  Sort Key: pm2_0.price, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=761.35..762.85 rows=75 width=59) (actual time=0.486..0.497 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=761.35..761.54 rows=75 width=59) (actual time=0.484..0.486 rows=80 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 36kB
              ->  Nested Loop Left Join  (cost=152.08..759.02 rows=75 width=59) (actual time=0.309..0.473 rows=80 loops=1)
                    ->  Nested Loop  (cost=151.64..590.08 rows=50 width=51) (actual time=0.300..0.411 rows=50 loops=1)
                          ->  Nested Loop  (cost=151.37..575.46 rows=50 width=38) (actual time=0.296..0.373 rows=50 loops=1)
                                ->  Limit  (cost=150.94..152.46 rows=50 width=16) (actual time=0.289..0.295 rows=50 loops=1)
                                      ->  Index Only Scan using ix_product_price_active on product pm1_0  (cost=0.43..144406.33 rows=4749460 width=16) (actual time=0.011..0.218 rows=5000 loops=1)
                                            Heap Fetches: 0
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=38) (actual time=0.001..0.001 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.001..0.001 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.001..0.001 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.548 ms
Execution Time: 0.561 ms
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

PostgreSQL 에서는 페이징만 다르다.

```sql
select pm1_0.id,pm1_0.name,pm1_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from product pm1_0
join brand bm1_0 on bm1_0.id=pm1_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm1_0.id
where pm1_0.deleted_at is null
group by pm1_0.id,bm1_0.id
order by count(plm1_0.id) desc,pm1_0.id desc
offset 4950 rows fetch first 50 rows only
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 38,378.8 (37,783.0~38,842.0) |
| 인덱스 적용 후 | 60,413.8 (59,842.0~61,197.0) |
| PostgreSQL (인덱스 적용) | 7,885.5 (7,481.9~8,478.5) |

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

**실행 계획 — PostgreSQL (인덱스 적용)**

```text
Limit  (cost=2453641.98..2453642.10 rows=50 width=59) (actual time=7805.347..7805.356 rows=50 loops=1)
  ->  Sort  (cost=2453629.60..2471451.62 rows=7128808 width=59) (actual time=7729.769..7729.906 rows=5000 loops=1)
        Sort Key: (count(plm1_0.id)) DESC, pm1_0.id DESC
        Sort Method: top-N heapsort  Memory: 1341kB
        ->  GroupAggregate  (cost=1837425.69..1980001.85 rows=7128808 width=59) (actual time=5897.738..7411.276 rows=4749743 loops=1)
              Group Key: pm1_0.id, bm1_0.id
              ->  Sort  (cost=1837425.69..1855247.71 rows=7128808 width=59) (actual time=5897.674..6484.044 rows=10210933 loops=1)
                    Sort Key: pm1_0.id DESC, bm1_0.id
                    Sort Method: external merge  Disk: 707456kB
                    ->  Hash Join  (cost=203213.90..489927.94 rows=7128808 width=59) (actual time=700.966..3190.602 rows=10210933 loops=1)
                          Hash Cond: (pm1_0.brand_id = bm1_0.id)
                          ->  Hash Right Join  (cost=203182.40..471104.01 rows=7128808 width=46) (actual time=700.849..2453.807 rows=10210933 loops=1)
                                Hash Cond: (plm1_0.product_id = pm1_0.id)
                                ->  Seq Scan on product_like plm1_0  (cost=0.00..137757.27 rows=7514027 width=16) (actual time=0.032..365.746 rows=7514034 loops=1)
                                ->  Hash  (cost=106826.80..106826.80 rows=4743648 width=38) (actual time=700.409..700.410 rows=4749743 loops=1)
                                      Buckets: 65536  Batches: 128  Memory Usage: 3127kB
                                      ->  Seq Scan on product pm1_0  (cost=0.00..106826.80 rows=4743648 width=38) (actual time=0.065..305.746 rows=4749743 loops=1)
                                            Filter: (deleted_at IS NULL)
                                            Rows Removed by Filter: 250257
                          ->  Hash  (cost=19.00..19.00 rows=1000 width=21) (actual time=0.112..0.112 rows=1000 loops=1)
                                Buckets: 1024  Batches: 1  Memory Usage: 61kB
                                ->  Seq Scan on brand bm1_0  (cost=0.00..19.00 rows=1000 width=21) (actual time=0.008..0.064 rows=1000 loops=1)
Planning Time: 0.680 ms
JIT:
  Functions: 27
  Options: Inlining true, Optimization true, Expressions true, Deforming true
  Timing: Generation 0.515 ms, Inlining 28.238 ms, Optimization 24.442 ms, Emission 22.741 ms, Total 75.937 ms
Execution Time: 7866.595 ms
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

PostgreSQL 에서는 페이징만 다르다.

```sql
select pm1_0.id,pm1_0.name,pm1_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from product pm1_0
join brand bm1_0 on bm1_0.id=pm1_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm1_0.id
where pm1_0.deleted_at is null and pm1_0.brand_id=1
group by pm1_0.id,bm1_0.id
order by pm1_0.created_at desc,pm1_0.id desc
offset 0 rows fetch first 50 rows only
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 5,605.4 (5,416.0~5,943.0) |
| 인덱스 적용 후 | 3,216.2 (3,129.0~3,477.0) |
| PostgreSQL (인덱스 적용) | 1,529.5 (1,452.0~1,799.0) |
| 쿼리 변경 후 (MySQL, 데이터를 다시 만든 뒤. 같은 데이터의 변경 전은 [Q07](#q07)) | 0.6 (0.5~0.6) |
| PostgreSQL (쿼리 변경 후) | 30.3 (27.9~32.5) |
| PostgreSQL (부분 인덱스 적용 후, [9장](#9-postgresql-부분-인덱스-적용-후)) | 0.2 (0.2~0.2) |

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

**실행 계획 — PostgreSQL (인덱스 적용)**

```text
Limit  (cost=454769.06..454769.18 rows=50 width=67) (actual time=1451.959..1451.966 rows=50 loops=1)
  ->  Sort  (cost=454769.06..455928.09 rows=463613 width=67) (actual time=1447.640..1447.644 rows=50 loops=1)
        Sort Key: pm1_0.created_at DESC, pm1_0.id DESC
        Sort Method: top-N heapsort  Memory: 36kB
        ->  HashAggregate  (cost=418402.59..439368.17 rows=463613 width=67) (actual time=1293.122..1413.357 rows=474476 loops=1)
              Group Key: pm1_0.id, bm1_0.id
              Planned Partitions: 32  Batches: 33  Memory Usage: 4113kB  Disk Usage: 95512kB
              ->  Nested Loop  (cost=92013.07..333924.93 rows=696723 width=67) (actual time=140.787..1159.917 rows=1028371 loops=1)
                    ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..8.29 rows=1 width=21) (actual time=0.006..0.014 rows=1 loops=1)
                          Index Cond: (id = 1)
                    ->  Hash Right Join  (cost=92012.79..326949.40 rows=696723 width=54) (actual time=140.778..1117.543 rows=1028371 loops=1)
                          Hash Cond: (plm1_0.product_id = pm1_0.id)
                          ->  Seq Scan on product_like plm1_0  (cost=0.00..137757.27 rows=7514027 width=16) (actual time=0.062..322.617 rows=7514034 loops=1)
                          ->  Hash  (cost=82142.63..82142.63 rows=463613 width=46) (actual time=140.447..140.448 rows=474476 loops=1)
                                Buckets: 65536  Batches: 16  Memory Usage: 2822kB
                                ->  Bitmap Heap Scan on product pm1_0  (cost=19520.47..82142.63 rows=463613 width=46) (actual time=22.279..101.943 rows=474476 loops=1)
                                      Recheck Cond: ((brand_id = 1) AND (deleted_at IS NULL))
                                      Heap Blocks: exact=56816
                                      ->  Bitmap Index Scan on ix_product_brand_price  (cost=0.00..19404.56 rows=463613 width=0) (actual time=17.291..17.291 rows=474476 loops=1)
                                            Index Cond: ((brand_id = 1) AND (deleted_at IS NULL))
Planning Time: 0.323 ms
JIT:
  Functions: 25
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.477 ms, Inlining 0.000 ms, Optimization 0.336 ms, Emission 5.737 ms, Total 6.550 ms
Execution Time: 1465.288 ms
```

**실행 계획 — MySQL 쿼리 변경 후** (SQL 은 [Q07](#q07))

```text
-> Sort: pm2_0.created_at DESC, pm2_0.id DESC  (actual time=0.641..0.642 rows=50 loops=1)
    -> Table scan on <temporary>  (actual time=0.621..0.624 rows=50 loops=1)
        -> Aggregate using temporary table  (actual time=0.62..0.62 rows=50 loops=1)
            -> Nested loop left join  (cost=95917 rows=206) (actual time=0.481..0.581 rows=77 loops=1)
                -> Nested loop inner join  (cost=95883 rows=50) (actual time=0.476..0.533 rows=50 loops=1)
                    -> Nested loop inner join  (cost=95866 rows=50) (actual time=0.473..0.522 rows=50 loops=1)
                        -> Table scan on pg1_0  (cost=95812..95815 rows=50) (actual time=0.467..0.47 rows=50 loops=1)
                            -> Materialize CTE page  (cost=95812..95812 rows=50) (actual time=0.465..0.465 rows=50 loops=1)
                                -> Limit: 50 row(s)  (cost=95807 rows=50) (actual time=0.449..0.454 rows=50 loops=1)
                                    -> Filter: (pm1_0.deleted_at is null)  (cost=95807 rows=946242) (actual time=0.448..0.452 rows=50 loops=1)
                                        -> Covering index lookup on pm1_0 using ix_product_brand_latest (brand_id=1, deleted_at=NULL) (reverse)  (cost=95807 rows=946242) (actual time=0.448..0.45 rows=50 loops=1)
                        -> Single-row index lookup on pm2_0 using PRIMARY (id=pg1_0.id)  (cost=0.913 rows=1) (actual time=935e-6..948e-6 rows=1 loops=50)
                    -> Single-row index lookup on bm1_0 using PRIMARY (id=pm2_0.brand_id)  (cost=0.252 rows=1) (actual time=107e-6..122e-6 rows=1 loops=50)
                -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pg1_0.id)  (cost=0.26 rows=4.11) (actual time=744e-6..850e-6 rows=1.06 loops=50)
```

**실행 계획 — PostgreSQL 쿼리 변경 후** (SQL 은 [Q07](#q07))

```text
Sort  (cost=34536.03..34536.22 rows=75 width=67) (actual time=31.348..32.537 rows=50 loops=1)
  Sort Key: pm2_0.created_at DESC, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=34532.20..34533.70 rows=75 width=67) (actual time=31.323..32.523 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=34532.20..34532.39 rows=75 width=67) (actual time=31.320..32.509 rows=81 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 36kB
              ->  Nested Loop Left Join  (cost=33918.61..34529.86 rows=75 width=67) (actual time=31.183..32.492 rows=81 loops=1)
                    ->  Nested Loop  (cost=33918.17..34360.93 rows=50 width=59) (actual time=31.166..32.430 rows=50 loops=1)
                          ->  Nested Loop  (cost=33917.90..34346.30 rows=50 width=46) (actual time=31.159..32.402 rows=50 loops=1)
                                ->  Limit  (cost=33917.47..33923.30 rows=50 width=16) (actual time=31.126..32.320 rows=50 loops=1)
                                      ->  Gather Merge  (cost=33917.47..78994.08 rows=386344 width=16) (actual time=31.126..32.317 rows=50 loops=1)
                                            Workers Planned: 2
                                            Workers Launched: 2
                                            ->  Sort  (cost=32917.44..33400.37 rows=193172 width=16) (actual time=30.305..30.306 rows=34 loops=3)
                                                  Sort Key: pm1_0.created_at DESC, pm1_0.id DESC
                                                  Sort Method: top-N heapsort  Memory: 29kB
                                                  Worker 0:  Sort Method: top-N heapsort  Memory: 29kB
                                                  Worker 1:  Sort Method: top-N heapsort  Memory: 29kB
                                                  ->  Parallel Index Only Scan using ix_product_brand_latest on product pm1_0  (cost=0.56..26500.41 rows=193172 width=16) (actual time=0.016..14.288 rows=158159 loops=3)
                                                        Index Cond: ((brand_id = 1) AND (deleted_at IS NULL))
                                                        Heap Fetches: 0
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=46) (actual time=0.001..0.001 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.000..0.000 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.001..0.001 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.424 ms
Execution Time: 32.589 ms
```

**실행 계획 — PostgreSQL 부분 인덱스 적용 후** (SQL 은 [Q07](#q07), 인덱스는 [9-1](#9-1-변경-내용))

```text
Sort  (cost=615.03..615.22 rows=75 width=67) (actual time=0.161..0.162 rows=50 loops=1)
  Sort Key: pm2_0.created_at DESC, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=611.20..612.70 rows=75 width=67) (actual time=0.137..0.149 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=611.20..611.38 rows=75 width=67) (actual time=0.135..0.137 rows=81 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 36kB
              ->  Nested Loop Left Join  (cost=1.57..608.86 rows=75 width=67) (actual time=0.021..0.123 rows=81 loops=1)
                    ->  Nested Loop  (cost=1.14..439.92 rows=50 width=59) (actual time=0.018..0.083 rows=50 loops=1)
                          ->  Nested Loop  (cost=0.86..425.30 rows=50 width=46) (actual time=0.016..0.062 rows=50 loops=1)
                                ->  Limit  (cost=0.43..2.30 rows=50 width=16) (actual time=0.010..0.014 rows=50 loops=1)
                                      ->  Index Only Scan Backward using ix_product_brand_latest_active on product pm1_0  (cost=0.43..17253.11 rows=462439 width=16) (actual time=0.009..0.012 rows=50 loops=1)
                                            Index Cond: (brand_id = 1)
                                            Heap Fetches: 0
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=46) (actual time=0.001..0.001 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.000..0.000 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.001..0.001 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.510 ms
Execution Time: 0.203 ms
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

PostgreSQL 에서는 페이징만 다르다.

```sql
select pm1_0.id,pm1_0.name,pm1_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from product pm1_0
join brand bm1_0 on bm1_0.id=pm1_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm1_0.id
where pm1_0.deleted_at is null and pm1_0.brand_id=1
group by pm1_0.id,bm1_0.id
order by pm1_0.price,pm1_0.id desc
offset 0 rows fetch first 50 rows only
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 5,222.6 (5,088.0~5,281.0) |
| 인덱스 적용 후 | 2,960.6 (2,915.0~3,102.0) |
| PostgreSQL (인덱스 적용) | 1,511.4 (1,432.2~1,660.5) |
| 쿼리 변경 후 (MySQL, 데이터를 다시 만든 뒤. 같은 데이터의 변경 전은 [Q08](#q08)) | 76.7 (75.5~77.4) |
| PostgreSQL (쿼리 변경 후) | 22.5 (21.4~23.8) |
| PostgreSQL (부분 인덱스 적용 후, [9장](#9-postgresql-부분-인덱스-적용-후)) | 0.3 (0.3~0.3) |

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

**실행 계획 — PostgreSQL (인덱스 적용)**

```text
Limit  (cost=447059.12..447059.25 rows=50 width=59) (actual time=1548.689..1548.694 rows=50 loops=1)
  ->  Sort  (cost=447059.12..448218.15 rows=463613 width=59) (actual time=1544.290..1544.294 rows=50 loops=1)
        Sort Key: pm1_0.price, pm1_0.id DESC
        Sort Method: top-N heapsort  Memory: 36kB
        ->  HashAggregate  (cost=412053.44..431658.23 rows=463613 width=59) (actual time=1402.539..1516.026 rows=474476 loops=1)
              Group Key: pm1_0.id, bm1_0.id
              Planned Partitions: 32  Batches: 33  Memory Usage: 4113kB  Disk Usage: 95264kB
              ->  Nested Loop  (cost=91560.07..333018.93 rows=696723 width=59) (actual time=162.499..1269.813 rows=1028371 loops=1)
                    ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..8.29 rows=1 width=21) (actual time=0.006..0.011 rows=1 loops=1)
                          Index Cond: (id = 1)
                    ->  Hash Right Join  (cost=91559.79..326043.40 rows=696723 width=46) (actual time=162.486..1227.117 rows=1028371 loops=1)
                          Hash Cond: (plm1_0.product_id = pm1_0.id)
                          ->  Seq Scan on product_like plm1_0  (cost=0.00..137757.27 rows=7514027 width=16) (actual time=0.071..382.371 rows=7514034 loops=1)
                          ->  Hash  (cost=82142.63..82142.63 rows=463613 width=38) (actual time=162.015..162.016 rows=474476 loops=1)
                                Buckets: 65536  Batches: 16  Memory Usage: 2591kB
                                ->  Bitmap Heap Scan on product pm1_0  (cost=19520.47..82142.63 rows=463613 width=38) (actual time=23.429..120.661 rows=474476 loops=1)
                                      Recheck Cond: ((brand_id = 1) AND (deleted_at IS NULL))
                                      Heap Blocks: exact=56816
                                      ->  Bitmap Index Scan on ix_product_brand_price  (cost=0.00..19404.56 rows=463613 width=0) (actual time=18.395..18.395 rows=474476 loops=1)
                                            Index Cond: ((brand_id = 1) AND (deleted_at IS NULL))
Planning Time: 0.336 ms
JIT:
  Functions: 25
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.595 ms, Inlining 0.000 ms, Optimization 0.367 ms, Emission 5.912 ms, Total 6.874 ms
Execution Time: 1562.341 ms
```

**실행 계획 — MySQL 쿼리 변경 후** (SQL 은 [Q08](#q08))

```text
-> Sort: pm2_0.price, pm2_0.id DESC  (actual time=77.4..77.4 rows=50 loops=1)
    -> Table scan on <temporary>  (actual time=77.3..77.3 rows=50 loops=1)
        -> Aggregate using temporary table  (actual time=77.3..77.3 rows=50 loops=1)
            -> Nested loop left join  (cost=135740 rows=206) (actual time=77.1..77.3 rows=96 loops=1)
                -> Nested loop inner join  (cost=135707 rows=50) (actual time=77.1..77.2 rows=50 loops=1)
                    -> Nested loop inner join  (cost=135689 rows=50) (actual time=77.1..77.2 rows=50 loops=1)
                        -> Table scan on pg1_0  (cost=135636..135639 rows=50) (actual time=77.1..77.1 rows=50 loops=1)
                            -> Materialize CTE page  (cost=135636..135636 rows=50) (actual time=77.1..77.1 rows=50 loops=1)
                                -> Limit: 50 row(s)  (cost=135631 rows=50) (actual time=77.1..77.1 rows=50 loops=1)
                                    -> Sort: pm1_0.price, pm1_0.id DESC, limit input to 50 row(s) per chunk  (cost=135631 rows=1.3e+6) (actual time=77.1..77.1 rows=50 loops=1)
                                        -> Filter: (pm1_0.deleted_at is null)  (cost=135631 rows=1.3e+6) (actual time=0.0287..57.4 rows=473653 loops=1)
                                            -> Index lookup on pm1_0 using ix_product_brand_price (brand_id=1, deleted_at=NULL)  (cost=135631 rows=1.3e+6) (actual time=0.0283..42.3 rows=473653 loops=1)
                        -> Single-row index lookup on pm2_0 using PRIMARY (id=pg1_0.id)  (cost=0.913 rows=1) (actual time=0.00254..0.00256 rows=1 loops=50)
                    -> Single-row index lookup on bm1_0 using PRIMARY (id=pm2_0.brand_id)  (cost=0.252 rows=1) (actual time=163e-6..175e-6 rows=1 loops=50)
                -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pg1_0.id)  (cost=0.26 rows=4.11) (actual time=0.00131..0.00142 rows=1.3 loops=50)
```

**실행 계획 — PostgreSQL 쿼리 변경 후** (SQL 은 [Q08](#q08))

```text
Sort  (cost=29371.91..29372.10 rows=75 width=59) (actual time=22.963..23.771 rows=50 loops=1)
  Sort Key: pm2_0.price, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=29368.07..29369.57 rows=75 width=59) (actual time=22.938..23.759 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=29368.07..29368.26 rows=75 width=59) (actual time=22.933..23.742 rows=95 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 38kB
              ->  Nested Loop Left Join  (cost=28754.48..29365.74 rows=75 width=59) (actual time=22.540..23.722 rows=95 loops=1)
                    ->  Nested Loop  (cost=28754.05..29196.80 rows=50 width=51) (actual time=22.522..23.560 rows=50 loops=1)
                          ->  Nested Loop  (cost=28753.77..29182.18 rows=50 width=38) (actual time=22.517..23.533 rows=50 loops=1)
                                ->  Limit  (cost=28753.34..28759.18 rows=50 width=16) (actual time=22.491..23.303 rows=50 loops=1)
                                      ->  Gather Merge  (cost=28753.34..73829.96 rows=386344 width=16) (actual time=22.489..23.301 rows=50 loops=1)
                                            Workers Planned: 2
                                            Workers Launched: 2
                                            ->  Sort  (cost=27753.32..28236.25 rows=193172 width=16) (actual time=21.711..21.712 rows=50 loops=3)
                                                  Sort Key: pm1_0.price, pm1_0.id DESC
                                                  Sort Method: top-N heapsort  Memory: 29kB
                                                  Worker 0:  Sort Method: top-N heapsort  Memory: 29kB
                                                  Worker 1:  Sort Method: top-N heapsort  Memory: 29kB
                                                  ->  Parallel Index Only Scan using ix_product_brand_price on product pm1_0  (cost=0.43..21336.28 rows=193172 width=16) (actual time=0.010..14.028 rows=158159 loops=3)
                                                        Index Cond: ((brand_id = 1) AND (deleted_at IS NULL))
                                                        Heap Fetches: 0
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=38) (actual time=0.004..0.004 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.000..0.000 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.003..0.003 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.439 ms
Execution Time: 23.818 ms
```

**실행 계획 — PostgreSQL 부분 인덱스 적용 후** (SQL 은 [Q08](#q08), 인덱스는 [9-1](#9-1-변경-내용))

```text
Sort  (cost=615.03..615.22 rows=75 width=59) (actual time=0.271..0.272 rows=50 loops=1)
  Sort Key: pm2_0.price, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=611.20..612.70 rows=75 width=59) (actual time=0.244..0.256 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=611.20..611.38 rows=75 width=59) (actual time=0.241..0.243 rows=95 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 38kB
              ->  Nested Loop Left Join  (cost=1.57..608.86 rows=75 width=59) (actual time=0.031..0.229 rows=95 loops=1)
                    ->  Nested Loop  (cost=1.14..439.92 rows=50 width=51) (actual time=0.027..0.155 rows=50 loops=1)
                          ->  Nested Loop  (cost=0.86..425.30 rows=50 width=38) (actual time=0.022..0.131 rows=50 loops=1)
                                ->  Limit  (cost=0.43..2.30 rows=50 width=16) (actual time=0.013..0.017 rows=50 loops=1)
                                      ->  Index Only Scan using ix_product_brand_price_active on product pm1_0  (cost=0.43..17261.11 rows=462439 width=16) (actual time=0.012..0.014 rows=50 loops=1)
                                            Index Cond: (brand_id = 1)
                                            Heap Fetches: 0
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=38) (actual time=0.002..0.002 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.000..0.000 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.001..0.001 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.470 ms
Execution Time: 0.312 ms
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

PostgreSQL 에서는 페이징만 다르다.

```sql
select pm1_0.id,pm1_0.name,pm1_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from product pm1_0
join brand bm1_0 on bm1_0.id=pm1_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm1_0.id
where pm1_0.deleted_at is null and pm1_0.brand_id=1
group by pm1_0.id,bm1_0.id
order by count(plm1_0.id) desc,pm1_0.id desc
offset 0 rows fetch first 50 rows only
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 5,144.8 (5,063.0~5,229.0) |
| 인덱스 적용 후 | 2,953.0 (2,910.0~3,059.0) |
| PostgreSQL (인덱스 적용) | 1,450.6 (1,426.0~1,518.9) |

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

**실행 계획 — PostgreSQL (인덱스 적용)**

```text
Limit  (cost=447059.12..447059.25 rows=50 width=59) (actual time=1426.435..1426.442 rows=50 loops=1)
  ->  Sort  (cost=447059.12..448218.15 rows=463613 width=59) (actual time=1422.292..1422.297 rows=50 loops=1)
        Sort Key: (count(plm1_0.id)) DESC, pm1_0.id DESC
        Sort Method: top-N heapsort  Memory: 37kB
        ->  HashAggregate  (cost=412053.44..431658.23 rows=463613 width=59) (actual time=1275.045..1391.553 rows=474476 loops=1)
              Group Key: pm1_0.id, bm1_0.id
              Planned Partitions: 32  Batches: 33  Memory Usage: 4113kB  Disk Usage: 95264kB
              ->  Nested Loop  (cost=91560.07..333018.93 rows=696723 width=59) (actual time=130.338..1144.587 rows=1028371 loops=1)
                    ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..8.29 rows=1 width=21) (actual time=0.006..0.010 rows=1 loops=1)
                          Index Cond: (id = 1)
                    ->  Hash Right Join  (cost=91559.79..326043.40 rows=696723 width=46) (actual time=130.329..1102.957 rows=1028371 loops=1)
                          Hash Cond: (plm1_0.product_id = pm1_0.id)
                          ->  Seq Scan on product_like plm1_0  (cost=0.00..137757.27 rows=7514027 width=16) (actual time=0.052..320.724 rows=7514034 loops=1)
                          ->  Hash  (cost=82142.63..82142.63 rows=463613 width=38) (actual time=130.037..130.038 rows=474476 loops=1)
                                Buckets: 65536  Batches: 16  Memory Usage: 2591kB
                                ->  Bitmap Heap Scan on product pm1_0  (cost=19520.47..82142.63 rows=463613 width=38) (actual time=21.652..95.876 rows=474476 loops=1)
                                      Recheck Cond: ((brand_id = 1) AND (deleted_at IS NULL))
                                      Heap Blocks: exact=56816
                                      ->  Bitmap Index Scan on ix_product_brand_price  (cost=0.00..19404.56 rows=463613 width=0) (actual time=16.679..16.679 rows=474476 loops=1)
                                            Index Cond: ((brand_id = 1) AND (deleted_at IS NULL))
Planning Time: 0.337 ms
JIT:
  Functions: 25
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.490 ms, Inlining 0.000 ms, Optimization 0.354 ms, Emission 5.678 ms, Total 6.522 ms
Execution Time: 1439.508 ms
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

PostgreSQL 에서는 페이징만 다르다.

```sql
select pm1_0.id,pm1_0.name,pm1_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from product pm1_0
join brand bm1_0 on bm1_0.id=pm1_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm1_0.id
where pm1_0.deleted_at is null and pm1_0.brand_id=1
group by pm1_0.id,bm1_0.id
order by pm1_0.created_at desc,pm1_0.id desc
offset 4950 rows fetch first 50 rows only
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 5,595.2 (5,394.0~5,720.0) |
| 인덱스 적용 후 | 3,165.2 (3,117.0~3,267.0) |
| PostgreSQL (인덱스 적용) | 1,467.6 (1,448.7~1,508.1) |
| 쿼리 변경 후 (MySQL, 데이터를 다시 만든 뒤. 같은 데이터의 변경 전은 [Q10](#q10)) | 1.3 (1.1~1.6) |
| PostgreSQL (쿼리 변경 후) | 41.2 (38.9~44.8) |
| PostgreSQL (부분 인덱스 적용 후, [9장](#9-postgresql-부분-인덱스-적용-후)) | 0.5 (0.4~0.5) |

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

**실행 계획 — PostgreSQL (인덱스 적용)**

```text
Limit  (cost=470182.32..470182.45 rows=50 width=67) (actual time=1508.132..1508.138 rows=50 loops=1)
  ->  Sort  (cost=470169.95..471328.98 rows=463613 width=67) (actual time=1503.545..1503.839 rows=5000 loops=1)
        Sort Key: pm1_0.created_at DESC, pm1_0.id DESC
        Sort Method: top-N heapsort  Memory: 1578kB
        ->  HashAggregate  (cost=418402.59..439368.17 rows=463613 width=67) (actual time=1346.559..1466.111 rows=474476 loops=1)
              Group Key: pm1_0.id, bm1_0.id
              Planned Partitions: 32  Batches: 33  Memory Usage: 4113kB  Disk Usage: 95512kB
              ->  Nested Loop  (cost=92013.07..333924.93 rows=696723 width=67) (actual time=132.234..1198.145 rows=1028371 loops=1)
                    ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..8.29 rows=1 width=21) (actual time=0.005..0.010 rows=1 loops=1)
                          Index Cond: (id = 1)
                    ->  Hash Right Join  (cost=92012.79..326949.40 rows=696723 width=54) (actual time=132.226..1154.003 rows=1028371 loops=1)
                          Hash Cond: (plm1_0.product_id = pm1_0.id)
                          ->  Seq Scan on product_like plm1_0  (cost=0.00..137757.27 rows=7514027 width=16) (actual time=0.050..325.926 rows=7514034 loops=1)
                          ->  Hash  (cost=82142.63..82142.63 rows=463613 width=46) (actual time=131.929..131.930 rows=474476 loops=1)
                                Buckets: 65536  Batches: 16  Memory Usage: 2822kB
                                ->  Bitmap Heap Scan on product pm1_0  (cost=19520.47..82142.63 rows=463613 width=46) (actual time=21.523..95.436 rows=474476 loops=1)
                                      Recheck Cond: ((brand_id = 1) AND (deleted_at IS NULL))
                                      Heap Blocks: exact=56816
                                      ->  Bitmap Index Scan on ix_product_brand_price  (cost=0.00..19404.56 rows=463613 width=0) (actual time=16.686..16.686 rows=474476 loops=1)
                                            Index Cond: ((brand_id = 1) AND (deleted_at IS NULL))
Planning Time: 0.307 ms
JIT:
  Functions: 25
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.475 ms, Inlining 0.000 ms, Optimization 0.358 ms, Emission 5.710 ms, Total 6.543 ms
Execution Time: 1521.033 ms
```

**실행 계획 — MySQL 쿼리 변경 후** (SQL 은 [Q10](#q10))

```text
-> Sort: pm2_0.created_at DESC, pm2_0.id DESC  (actual time=1.21..1.21 rows=50 loops=1)
    -> Table scan on <temporary>  (actual time=1.19..1.2 rows=50 loops=1)
        -> Aggregate using temporary table  (actual time=1.19..1.19 rows=50 loops=1)
            -> Nested loop left join  (cost=102908 rows=206) (actual time=1.09..1.17 rows=59 loops=1)
                -> Nested loop inner join  (cost=101630 rows=50) (actual time=1.09..1.13 rows=50 loops=1)
                    -> Nested loop inner join  (cost=100375 rows=50) (actual time=1.09..1.12 rows=50 loops=1)
                        -> Table scan on pg1_0  (cost=95812..95815 rows=50) (actual time=1.07..1.07 rows=50 loops=1)
                            -> Materialize CTE page  (cost=95812..95812 rows=50) (actual time=1.06..1.06 rows=50 loops=1)
                                -> Limit/Offset: 50/4950 row(s)  (cost=95807 rows=50) (actual time=1.05..1.06 rows=50 loops=1)
                                    -> Filter: (pm1_0.deleted_at is null)  (cost=95807 rows=946242) (actual time=0.423..0.977 rows=5000 loops=1)
                                        -> Covering index lookup on pm1_0 using ix_product_brand_latest (brand_id=1, deleted_at=NULL) (reverse)  (cost=95807 rows=946242) (actual time=0.423..0.829 rows=5000 loops=1)
                        -> Single-row index lookup on pm2_0 using PRIMARY (id=pg1_0.id)  (cost=0.911 rows=1) (actual time=911e-6..928e-6 rows=1 loops=50)
                    -> Single-row index lookup on bm1_0 using PRIMARY (id=pm2_0.brand_id)  (cost=0.25 rows=1) (actual time=92.5e-6..111e-6 rows=1 loops=50)
                -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pg1_0.id)  (cost=0.252 rows=4.11) (actual time=623e-6..662e-6 rows=0.42 loops=50)
```

**실행 계획 — PostgreSQL 쿼리 변경 후** (SQL 은 [Q10](#q10))

```text
Sort  (cost=41530.61..41530.80 rows=75 width=67) (actual time=41.408..42.389 rows=50 loops=1)
  Sort Key: pm2_0.created_at DESC, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=41526.77..41528.27 rows=75 width=67) (actual time=41.388..42.378 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=41526.77..41526.96 rows=75 width=67) (actual time=41.386..42.366 rows=64 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 34kB
              ->  Nested Loop Left Join  (cost=40913.18..41524.44 rows=75 width=67) (actual time=41.278..42.353 rows=64 loops=1)
                    ->  Nested Loop  (cost=40912.75..41355.50 rows=50 width=59) (actual time=41.274..42.314 rows=50 loops=1)
                          ->  Nested Loop  (cost=40912.47..41340.88 rows=50 width=46) (actual time=41.270..42.291 rows=50 loops=1)
                                ->  Limit  (cost=40912.04..40917.88 rows=50 width=16) (actual time=41.252..42.235 rows=50 loops=1)
                                      ->  Gather Merge  (cost=40334.50..85411.12 rows=386344 width=16) (actual time=40.802..42.148 rows=5000 loops=1)
                                            Workers Planned: 2
                                            Workers Launched: 2
                                            ->  Sort  (cost=39334.48..39817.41 rows=193172 width=16) (actual time=39.691..39.752 rows=2703 loops=3)
                                                  Sort Key: pm1_0.created_at DESC, pm1_0.id DESC
                                                  Sort Method: top-N heapsort  Memory: 853kB
                                                  Worker 0:  Sort Method: top-N heapsort  Memory: 853kB
                                                  Worker 1:  Sort Method: top-N heapsort  Memory: 853kB
                                                  ->  Parallel Index Only Scan using ix_product_brand_latest on product pm1_0  (cost=0.56..26500.41 rows=193172 width=16) (actual time=0.014..14.395 rows=158159 loops=3)
                                                        Index Cond: ((brand_id = 1) AND (deleted_at IS NULL))
                                                        Heap Fetches: 0
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=46) (actual time=0.001..0.001 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.000..0.000 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.001..0.001 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.435 ms
Execution Time: 42.456 ms
```

**실행 계획 — PostgreSQL 부분 인덱스 적용 후** (SQL 은 [Q10](#q10), 인덱스는 [9-1](#9-1-변경-내용))

```text
Sort  (cost=799.71..799.89 rows=75 width=67) (actual time=0.456..0.458 rows=50 loops=1)
  Sort Key: pm2_0.created_at DESC, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=795.87..797.37 rows=75 width=67) (actual time=0.434..0.445 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=795.87..796.06 rows=75 width=67) (actual time=0.431..0.433 rows=64 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 34kB
              ->  Nested Loop Left Join  (cost=186.25..793.53 rows=75 width=67) (actual time=0.341..0.423 rows=64 loops=1)
                    ->  Nested Loop  (cost=185.81..624.60 rows=50 width=59) (actual time=0.338..0.391 rows=50 loops=1)
                          ->  Nested Loop  (cost=185.54..609.97 rows=50 width=46) (actual time=0.335..0.371 rows=50 loops=1)
                                ->  Limit  (cost=185.11..186.97 rows=50 width=16) (actual time=0.327..0.331 rows=50 loops=1)
                                      ->  Index Only Scan Backward using ix_product_brand_latest_active on product pm1_0  (cost=0.43..17253.11 rows=462439 width=16) (actual time=0.008..0.249 rows=5000 loops=1)
                                            Index Cond: (brand_id = 1)
                                            Heap Fetches: 0
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=46) (actual time=0.001..0.001 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.000..0.000 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.000..0.001 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.525 ms
Execution Time: 0.502 ms
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

PostgreSQL 에서는 페이징만 다르다.

```sql
select pm1_0.id,pm1_0.name,pm1_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from product pm1_0
join brand bm1_0 on bm1_0.id=pm1_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm1_0.id
where pm1_0.deleted_at is null and pm1_0.brand_id=1
group by pm1_0.id,bm1_0.id
order by pm1_0.price,pm1_0.id desc
offset 4950 rows fetch first 50 rows only
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 5,305.6 (5,231.0~5,440.0) |
| 인덱스 적용 후 | 2,975.0 (2,965.0~3,007.0) |
| PostgreSQL (인덱스 적용) | 1,520.4 (1,454.9~1,623.6) |
| 쿼리 변경 후 (MySQL, 데이터를 다시 만든 뒤. 같은 데이터의 변경 전은 [Q11](#q11)) | 93.5 (91.7~96.5) |
| PostgreSQL (쿼리 변경 후) | 25.6 (23.0~30.4) |
| PostgreSQL (부분 인덱스 적용 후, [9장](#9-postgresql-부분-인덱스-적용-후)) | 0.6 (0.6~0.6) |

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

**실행 계획 — PostgreSQL (인덱스 적용)**

```text
Limit  (cost=462472.39..462472.51 rows=50 width=59) (actual time=1475.265..1475.270 rows=50 loops=1)
  ->  Sort  (cost=462460.01..463619.04 rows=463613 width=59) (actual time=1470.808..1470.920 rows=5000 loops=1)
        Sort Key: pm1_0.price, pm1_0.id DESC
        Sort Method: top-N heapsort  Memory: 1581kB
        ->  HashAggregate  (cost=412053.44..431658.23 rows=463613 width=59) (actual time=1319.851..1436.027 rows=474476 loops=1)
              Group Key: pm1_0.id, bm1_0.id
              Planned Partitions: 32  Batches: 33  Memory Usage: 4113kB  Disk Usage: 95264kB
              ->  Nested Loop  (cost=91560.07..333018.93 rows=696723 width=59) (actual time=138.546..1185.490 rows=1028371 loops=1)
                    ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..8.29 rows=1 width=21) (actual time=0.007..0.014 rows=1 loops=1)
                          Index Cond: (id = 1)
                    ->  Hash Right Join  (cost=91559.79..326043.40 rows=696723 width=46) (actual time=138.536..1143.486 rows=1028371 loops=1)
                          Hash Cond: (plm1_0.product_id = pm1_0.id)
                          ->  Seq Scan on product_like plm1_0  (cost=0.00..137757.27 rows=7514027 width=16) (actual time=0.078..330.697 rows=7514034 loops=1)
                          ->  Hash  (cost=82142.63..82142.63 rows=463613 width=38) (actual time=138.168..138.169 rows=474476 loops=1)
                                Buckets: 65536  Batches: 16  Memory Usage: 2591kB
                                ->  Bitmap Heap Scan on product pm1_0  (cost=19520.47..82142.63 rows=463613 width=38) (actual time=21.911..102.260 rows=474476 loops=1)
                                      Recheck Cond: ((brand_id = 1) AND (deleted_at IS NULL))
                                      Heap Blocks: exact=56816
                                      ->  Bitmap Index Scan on ix_product_brand_price  (cost=0.00..19404.56 rows=463613 width=0) (actual time=16.952..16.952 rows=474476 loops=1)
                                            Index Cond: ((brand_id = 1) AND (deleted_at IS NULL))
Planning Time: 0.319 ms
JIT:
  Functions: 25
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.548 ms, Inlining 0.000 ms, Optimization 0.351 ms, Emission 5.868 ms, Total 6.767 ms
Execution Time: 1488.453 ms
```

**실행 계획 — MySQL 쿼리 변경 후** (SQL 은 [Q11](#q11))

```text
-> Sort: pm2_0.price, pm2_0.id DESC  (actual time=94.3..94.3 rows=50 loops=1)
    -> Table scan on <temporary>  (actual time=94.3..94.3 rows=50 loops=1)
        -> Aggregate using temporary table  (actual time=94.3..94.3 rows=50 loops=1)
            -> Nested loop left join  (cost=142731 rows=206) (actual time=94..94.2 rows=68 loops=1)
                -> Nested loop inner join  (cost=141453 rows=50) (actual time=94..94.2 rows=50 loops=1)
                    -> Nested loop inner join  (cost=140198 rows=50) (actual time=94..94.2 rows=50 loops=1)
                        -> Table scan on pg1_0  (cost=135636..135639 rows=50) (actual time=94..94 rows=50 loops=1)
                            -> Materialize CTE page  (cost=135636..135636 rows=50) (actual time=94..94 rows=50 loops=1)
                                -> Limit/Offset: 50/4950 row(s)  (cost=135631 rows=50) (actual time=94..94 rows=50 loops=1)
                                    -> Sort: pm1_0.price, pm1_0.id DESC, limit input to 5000 row(s) per chunk  (cost=135631 rows=1.3e+6) (actual time=93.8..93.9 rows=5000 loops=1)
                                        -> Filter: (pm1_0.deleted_at is null)  (cost=135631 rows=1.3e+6) (actual time=0.0315..58.6 rows=473653 loops=1)
                                            -> Index lookup on pm1_0 using ix_product_brand_price (brand_id=1, deleted_at=NULL)  (cost=135631 rows=1.3e+6) (actual time=0.031..44.3 rows=473653 loops=1)
                        -> Single-row index lookup on pm2_0 using PRIMARY (id=pg1_0.id)  (cost=0.911 rows=1) (actual time=0.00262..0.00263 rows=1 loops=50)
                    -> Single-row index lookup on bm1_0 using PRIMARY (id=pm2_0.brand_id)  (cost=0.25 rows=1) (actual time=152e-6..163e-6 rows=1 loops=50)
                -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pg1_0.id)  (cost=0.252 rows=4.11) (actual time=0.00119..0.00124 rows=0.56 loops=50)
```

**실행 계획 — PostgreSQL 쿼리 변경 후** (SQL 은 [Q11](#q11))

```text
Sort  (cost=36366.48..36366.67 rows=75 width=59) (actual time=22.049..23.002 rows=50 loops=1)
  Sort Key: pm2_0.price, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=36362.65..36364.15 rows=75 width=59) (actual time=22.024..22.991 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=36362.65..36362.84 rows=75 width=59) (actual time=22.021..22.976 rows=155 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 46kB
              ->  Nested Loop Left Join  (cost=35749.06..36360.31 rows=75 width=59) (actual time=21.745..22.957 rows=155 loops=1)
                    ->  Nested Loop  (cost=35748.62..36191.38 rows=50 width=51) (actual time=21.735..22.860 rows=50 loops=1)
                          ->  Nested Loop  (cost=35748.35..36176.75 rows=50 width=38) (actual time=21.732..22.837 rows=50 loops=1)
                                ->  Limit  (cost=35747.92..35753.75 rows=50 width=16) (actual time=21.711..22.666 rows=50 loops=1)
                                      ->  Gather Merge  (cost=35170.38..80246.99 rows=386344 width=16) (actual time=21.278..22.584 rows=5000 loops=1)
                                            Workers Planned: 2
                                            Workers Launched: 2
                                            ->  Sort  (cost=34170.35..34653.28 rows=193172 width=16) (actual time=20.400..20.461 rows=2682 loops=3)
                                                  Sort Key: pm1_0.price, pm1_0.id DESC
                                                  Sort Method: top-N heapsort  Memory: 781kB
                                                  Worker 0:  Sort Method: top-N heapsort  Memory: 636kB
                                                  Worker 1:  Sort Method: top-N heapsort  Memory: 635kB
                                                  ->  Parallel Index Only Scan using ix_product_brand_price on product pm1_0  (cost=0.43..21336.28 rows=193172 width=16) (actual time=0.010..12.442 rows=158159 loops=3)
                                                        Index Cond: ((brand_id = 1) AND (deleted_at IS NULL))
                                                        Heap Fetches: 0
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=38) (actual time=0.003..0.003 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.000..0.000 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.002..0.002 rows=2 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.418 ms
Execution Time: 23.068 ms
```

**실행 계획 — PostgreSQL 부분 인덱스 적용 후** (SQL 은 [Q11](#q11), 인덱스는 [9-1](#9-1-변경-내용))

```text
Sort  (cost=799.79..799.98 rows=75 width=59) (actual time=0.558..0.560 rows=50 loops=1)
  Sort Key: pm2_0.price, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=795.96..797.46 rows=75 width=59) (actual time=0.524..0.540 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=795.96..796.14 rows=75 width=59) (actual time=0.521..0.525 rows=155 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 46kB
              ->  Nested Loop Left Join  (cost=186.33..793.62 rows=75 width=59) (actual time=0.336..0.505 rows=155 loops=1)
                    ->  Nested Loop  (cost=185.90..624.68 rows=50 width=51) (actual time=0.332..0.429 rows=50 loops=1)
                          ->  Nested Loop  (cost=185.63..610.06 rows=50 width=38) (actual time=0.329..0.407 rows=50 loops=1)
                                ->  Limit  (cost=185.19..187.06 rows=50 width=16) (actual time=0.325..0.329 rows=50 loops=1)
                                      ->  Index Only Scan using ix_product_brand_price_active on product pm1_0  (cost=0.43..17261.11 rows=462439 width=16) (actual time=0.011..0.250 rows=5000 loops=1)
                                            Index Cond: (brand_id = 1)
                                            Heap Fetches: 0
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=38) (actual time=0.001..0.001 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.000..0.000 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.001..0.001 rows=2 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.469 ms
Execution Time: 0.598 ms
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

PostgreSQL 에서는 페이징만 다르다.

```sql
select pm1_0.id,pm1_0.name,pm1_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from product pm1_0
join brand bm1_0 on bm1_0.id=pm1_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm1_0.id
where pm1_0.deleted_at is null and pm1_0.brand_id=1
group by pm1_0.id,bm1_0.id
order by count(plm1_0.id) desc,pm1_0.id desc
offset 4950 rows fetch first 50 rows only
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 5,236.6 (5,163.0~5,423.0) |
| 인덱스 적용 후 | 2,982.0 (2,936.0~3,093.0) |
| PostgreSQL (인덱스 적용) | 1,486.2 (1,442.7~1,572.2) |

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

**실행 계획 — PostgreSQL (인덱스 적용)**

```text
Limit  (cost=462472.39..462472.51 rows=50 width=59) (actual time=1450.457..1450.463 rows=50 loops=1)
  ->  Sort  (cost=462460.01..463619.04 rows=463613 width=59) (actual time=1445.988..1446.098 rows=5000 loops=1)
        Sort Key: (count(plm1_0.id)) DESC, pm1_0.id DESC
        Sort Method: top-N heapsort  Memory: 1580kB
        ->  HashAggregate  (cost=412053.44..431658.23 rows=463613 width=59) (actual time=1293.167..1409.020 rows=474476 loops=1)
              Group Key: pm1_0.id, bm1_0.id
              Planned Partitions: 32  Batches: 33  Memory Usage: 4113kB  Disk Usage: 95264kB
              ->  Nested Loop  (cost=91560.07..333018.93 rows=696723 width=59) (actual time=134.708..1159.445 rows=1028371 loops=1)
                    ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..8.29 rows=1 width=21) (actual time=0.006..0.011 rows=1 loops=1)
                          Index Cond: (id = 1)
                    ->  Hash Right Join  (cost=91559.79..326043.40 rows=696723 width=46) (actual time=134.698..1117.413 rows=1028371 loops=1)
                          Hash Cond: (plm1_0.product_id = pm1_0.id)
                          ->  Seq Scan on product_like plm1_0  (cost=0.00..137757.27 rows=7514027 width=16) (actual time=0.080..324.391 rows=7514034 loops=1)
                          ->  Hash  (cost=82142.63..82142.63 rows=463613 width=38) (actual time=134.326..134.327 rows=474476 loops=1)
                                Buckets: 65536  Batches: 16  Memory Usage: 2591kB
                                ->  Bitmap Heap Scan on product pm1_0  (cost=19520.47..82142.63 rows=463613 width=38) (actual time=22.267..99.644 rows=474476 loops=1)
                                      Recheck Cond: ((brand_id = 1) AND (deleted_at IS NULL))
                                      Heap Blocks: exact=56816
                                      ->  Bitmap Index Scan on ix_product_brand_price  (cost=0.00..19404.56 rows=463613 width=0) (actual time=17.280..17.281 rows=474476 loops=1)
                                            Index Cond: ((brand_id = 1) AND (deleted_at IS NULL))
Planning Time: 0.307 ms
JIT:
  Functions: 25
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.543 ms, Inlining 0.000 ms, Optimization 0.492 ms, Emission 6.451 ms, Total 7.486 ms
Execution Time: 1464.918 ms
```

## 6. count 쿼리 상세

### C01

전체 — L01~L06 의 `totalCount`

```sql
select count(pm1_0.id) from product pm1_0 where pm1_0.deleted_at is null
```

PostgreSQL 에서도 같은 SQL 이다.

> 짝이 되는 목록 쿼리가 끝나지 않아 general log 로 잡지 못했다. C02 에서 브랜드 조건만 뺀 형태로 추론.

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 968.6 (956.0~998.0) |
| 인덱스 적용 후 | 858.8 (854.0~867.0) |
| PostgreSQL (인덱스 적용) | 125.0 (118.8~137.0) |

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

**실행 계획 — PostgreSQL (인덱스 적용)**

```text
Finalize Aggregate  (cost=83601.76..83601.77 rows=1 width=8) (actual time=120.866..124.863 rows=1 loops=1)
  ->  Gather  (cost=83601.55..83601.76 rows=2 width=8) (actual time=120.772..124.860 rows=3 loops=1)
        Workers Planned: 2
        Workers Launched: 2
        ->  Partial Aggregate  (cost=82601.55..82601.56 rows=1 width=8) (actual time=120.002..120.003 rows=1 loops=3)
              ->  Parallel Seq Scan on product pm1_0  (cost=0.00..77660.25 rows=1976520 width=8) (actual time=0.008..72.388 rows=1583248 loops=3)
                    Filter: (deleted_at IS NULL)
                    Rows Removed by Filter: 83419
Planning Time: 0.137 ms
Execution Time: 124.889 ms
```

### C02

1번 브랜드 — L07~L12 의 `totalCount`

```sql
select count(pm1_0.id) from product pm1_0 where pm1_0.deleted_at is null and pm1_0.brand_id=1
```

PostgreSQL 에서도 같은 SQL 이다.

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| 인덱스 적용 전 | 896.6 (876.0~963.0) |
| 인덱스 적용 후 | 98.1 (97.1~99.5) |
| PostgreSQL (인덱스 적용) | 20.2 (19.3~21.1) |

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

**실행 계획 — PostgreSQL (인덱스 적용)**

```text
Finalize Aggregate  (cost=22819.43..22819.44 rows=1 width=8) (actual time=19.060..20.342 rows=1 loops=1)
  ->  Gather  (cost=22819.21..22819.42 rows=2 width=8) (actual time=19.021..20.338 rows=3 loops=1)
        Workers Planned: 2
        Workers Launched: 2
        ->  Partial Aggregate  (cost=21819.21..21819.22 rows=1 width=8) (actual time=18.184..18.184 rows=1 loops=3)
              ->  Parallel Index Only Scan using ix_product_brand_price on product pm1_0  (cost=0.43..21336.28 rows=193172 width=8) (actual time=0.040..13.541 rows=158159 loops=3)
                    Index Cond: ((brand_id = 1) AND (deleted_at IS NULL))
                    Heap Fetches: 0
Planning Time: 0.183 ms
Execution Time: 20.370 ms
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

## 8. 쿼리 변경 후 검증

7-5 의 첫 번째 다음 단계(`latest`·`price_asc` 는 페이지의 상품 50개를 먼저 고른 뒤 붙이기)를 적용하고, MySQL·PostgreSQL 에서 같은 케이스를 다시 측정한다.

### 8-1. 변경 내용

- `latest`·`price_asc` 목록 쿼리를 CTE 하나로 바꿨다 (`ProductQueryRepositoryImpl.findActivePageByProductOrder`, HQL. Querydsl JPA 는 CTE 를 지원하지 않는다).
  - CTE `page` 에서 `product` 만으로 정렬·페이징해 상품 id 50개를 고른다.
  - 바깥에서 그 50건에만 `product` PK 조회, `brand` 조인, `product_like` 집계를 하고, 같은 키로 다시 정렬한다. 조인을 거치면 CTE 의 순서가 보장되지 않기 때문이다.
- `likes_desc`(L03·L06·L09·L12) 와 count 쿼리(C01·C02) 는 바꾸지 않았다. 그래서 다시 재지 않았다.
- 인덱스는 그대로다 (1장 "인덱스 적용 후").

### 8-2. 검증 방법

**실제 SQL 확인**

- 앱을 띄워 8-4 의 케이스별 URL 8개를 호출하고, DB 로그로 실제 SQL 을 잡았다. 8개 모두 200 이었다. 케이스별 SQL 은 로그 그대로이고, 줄만 바꿨다.
  - MySQL: `local` 프로필. general log 를 잠시 켜고(`log_output = TABLE`) `mysql.general_log` 에서 읽었다. 확인 뒤 `general_log = OFF`, `log_output = FILE` 로 되돌리고 로그 테이블을 비웠다. 값이 들어간 채로 찍힌다.
  - PostgreSQL: `local-pg` 프로필. `log_statement = all` 을 잠시 켜고(`ALTER SYSTEM`, 확인 뒤 `RESET` 으로 되돌림) statement 로그로 잡았다. 앱은 `brand_id`·offset·limit 을 바인딩 변수(`$1` ..)로 보내므로, 로그의 `parameters` 값을 그 자리에 넣었다.
- 두 DB 의 SQL 은 CTE 안 페이징만 다르다 (`limit 4950,50` / `offset 4950 rows fetch first 50 rows only`). MySQL SQL 의 페이징만 바꾸면 PostgreSQL 로그의 SQL 과 글자까지 같다.
- 아래는 HQL 에 쓴 대로가 아니라 Hibernate 가 바꿔서 낸 부분이다.
  - CTE 열 목록 `page (id)`
  - CTE 안 페이징 (위)
  - `price asc` 는 `price` 로 나갔다 (기본이 오름차순이라 같은 뜻).
- 정렬 키는 CTE 안·밖이 같다. 동률은 `id desc` 다 (ASM-08).

**결과가 같은지 확인**

- DB 마다 같은 데이터에서 변경 전 SQL(5장의 케이스별 SQL)과 변경 후 SQL 을 실행해 출력을 비교했다.
- 비교 항목은 상품 id·이름·가격, 브랜드 id·이름, 좋아요 수, 그리고 행 순서다.
- MySQL·PostgreSQL 모두 8개 케이스에서 50행이 완전히 같았다.
- 자동 테스트: `./gradlew :apps:commerce-api:check` (Testcontainers MySQL) 290개 통과.

**측정**

- `bench.py`·`bench_pg.py` 로 2장과 같은 방식(워밍업 1회 + 측정 5회, 순차 실행, 맨 위 노드 `actual time` 끝 값)으로 쟀다. 케이스 ID 는 `Q` + 대응하는 L 번호다.
  ```
  python3 docs/week4/bench.py cte Q01 Q02 Q04 Q05 Q07 Q08 Q10 Q11
  python3 docs/week4/bench_pg.py pg-cte Q01 Q02 Q04 Q05 Q07 Q08 Q10 Q11
  ```
  원본 결과는 `docs/week4/results/cte/`(MySQL), `docs/week4/results/pg-cte/`(PostgreSQL) 에 남는다.
- MySQL 은 데이터를 `seed.sql` 로 다시 만든 뒤 쟀다 (볼륨이 없어져 새로 적재). 규모·분포는 같지만 무작위 값이라 4-1 을 잰 데이터와 행 단위로 같지 않다.
  - 상품 5,000,000 (삭제 250,544) / 좋아요 7,636,480. `brandId=1` 은 삭제되지 않은 상품 473,653개, 좋아요 1,000개 이상 상품 24개, 최대 좋아요 9,681.
  - 그래서 MySQL "변경 전" 은 같은 데이터에서 변경 전 쿼리(L 케이스)를 다시 잰 값이다 (`bench.py after-reseed …`, `results/after-reseed/`).
- PostgreSQL "변경 전" 은 4-1 의 "PostgreSQL (인덱스 적용)" 값(`results/pg-after/`)이다. 데이터·인덱스·설정은 같다.

### 8-3. 결과 요약 (평균, ms)

**MySQL**

| ID | 대응 | 브랜드 | 페이지 | 정렬 | 변경 전 | 변경 후 | 변화 |
|---|---|---|---|---|---|---|---|
| [Q01](#q01) | [L01](#l01) | 전체 | 첫 페이지 | `latest` | 44,669.0 (41,906.0~49,258.0) | 0.7 (0.7~0.8) | 약 6만 배 |
| [Q02](#q02) | [L02](#l02) | 전체 | 첫 페이지 | `price_asc` | 41,103.8 (39,713.0~43,154.0) | 680.8 (676.0~684.0) | 약 60배 |
| [Q04](#q04) | [L04](#l04) | 전체 | 딥 페이지 | `latest` | 45,478.6 (44,073.0~48,059.0) | 1.1 (1.0~1.2) | 약 4만 배 |
| [Q05](#q05) | [L05](#l05) | 전체 | 딥 페이지 | `price_asc` | 42,912.6 (41,666.0~44,605.0) | 683.8 (678.0~690.0) | 약 63배 |
| [Q07](#q07) | [L07](#l07) | 1번 브랜드 | 첫 페이지 | `latest` | 2,192.2 (2,155.0~2,296.0) | 0.6 (0.5~0.6) | 약 3,700배 |
| [Q08](#q08) | [L08](#l08) | 1번 브랜드 | 첫 페이지 | `price_asc` | 2,019.4 (1,959.0~2,103.0) | 76.7 (75.5~77.4) | 약 26배 |
| [Q10](#q10) | [L10](#l10) | 1번 브랜드 | 딥 페이지 | `latest` | 2,163.8 (2,102.0~2,264.0) | 1.3 (1.1~1.6) | 약 1,700배 |
| [Q11](#q11) | [L11](#l11) | 1번 브랜드 | 딥 페이지 | `price_asc` | 2,047.8 (2,011.0~2,085.0) | 93.5 (91.7~96.5) | 약 22배 |

**PostgreSQL**

| ID | 대응 | 브랜드 | 페이지 | 정렬 | 변경 전 | 변경 후 | 변화 |
|---|---|---|---|---|---|---|---|
| [Q01](#q01) | [L01](#l01) | 전체 | 첫 페이지 | `latest` | 7,855.1 (7,237.1~8,464.0) | 227.2 (225.4~229.2) | 약 35배 |
| [Q02](#q02) | [L02](#l02) | 전체 | 첫 페이지 | `price_asc` | 7,441.3 (7,146.8~7,809.0) | 185.4 (183.7~186.9) | 약 40배 |
| [Q04](#q04) | [L04](#l04) | 전체 | 딥 페이지 | `latest` | 7,556.0 (7,304.7~7,857.6) | 303.7 (301.5~306.9) | 약 25배 |
| [Q05](#q05) | [L05](#l05) | 전체 | 딥 페이지 | `price_asc` | 7,370.5 (7,078.3~7,962.0) | 195.2 (193.7~198.3) | 약 38배 |
| [Q07](#q07) | [L07](#l07) | 1번 브랜드 | 첫 페이지 | `latest` | 1,529.5 (1,452.0~1,799.0) | 30.3 (27.9~32.5) | 약 50배 |
| [Q08](#q08) | [L08](#l08) | 1번 브랜드 | 첫 페이지 | `price_asc` | 1,511.4 (1,432.2~1,660.5) | 22.5 (21.4~23.8) | 약 67배 |
| [Q10](#q10) | [L10](#l10) | 1번 브랜드 | 딥 페이지 | `latest` | 1,467.6 (1,448.7~1,508.1) | 41.2 (38.9~44.8) | 약 36배 |
| [Q11](#q11) | [L11](#l11) | 1번 브랜드 | 딥 페이지 | `price_asc` | 1,520.4 (1,454.9~1,623.6) | 25.6 (23.0~30.4) | 약 59배 |

### 8-4. 케이스별 상세

### Q01

[L01](#l01) 의 변경 후 — 전체 · 첫 페이지 (`page=0`) · `latest` — `/api/v1/products?sort=latest&page=0&size=50`

```sql
with page (id) as (
    select pm1_0.id
    from product pm1_0
    where pm1_0.deleted_at is null
    order by pm1_0.created_at desc,pm1_0.id desc
    limit 0,50)
select pm2_0.id,pm2_0.name,pm2_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from page pg1_0
join product pm2_0 on pm2_0.id=pg1_0.id
join brand bm1_0 on bm1_0.id=pm2_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm2_0.id
group by pm2_0.id,bm1_0.id
order by pm2_0.created_at desc,pm2_0.id desc
```

PostgreSQL 에서는 페이징만 다르다.

```sql
with page (id) as (
    select pm1_0.id
    from product pm1_0
    where pm1_0.deleted_at is null
    order by pm1_0.created_at desc,pm1_0.id desc
    offset 0 rows fetch first 50 rows only)
select pm2_0.id,pm2_0.name,pm2_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from page pg1_0
join product pm2_0 on pm2_0.id=pg1_0.id
join brand bm1_0 on bm1_0.id=pm2_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm2_0.id
group by pm2_0.id,bm1_0.id
order by pm2_0.created_at desc,pm2_0.id desc
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| MySQL 변경 전 (L01, 같은 데이터에서 다시 잼) | 44,669.0 (41,906.0~49,258.0) |
| MySQL 변경 후 | 0.7 (0.7~0.8) |
| PostgreSQL 변경 전 (L01) | 7,855.1 (7,237.1~8,464.0) |
| PostgreSQL 변경 후 | 227.2 (225.4~229.2) |

**실행 계획 — MySQL 변경 후**

```text
-> Sort: pm2_0.created_at DESC, pm2_0.id DESC  (actual time=0.683..0.684 rows=50 loops=1)
    -> Table scan on <temporary>  (actual time=0.668..0.671 rows=50 loops=1)
        -> Aggregate using temporary table  (actual time=0.668..0.668 rows=50 loops=1)
            -> Nested loop left join  (cost=232793 rows=206) (actual time=0.507..0.624 rows=107 loops=1)
                -> Nested loop inner join  (cost=232759 rows=50) (actual time=0.502..0.564 rows=50 loops=1)
                    -> Nested loop inner join  (cost=232742 rows=50) (actual time=0.501..0.533 rows=50 loops=1)
                        -> Table scan on pg1_0  (cost=232688..232691 rows=50) (actual time=0.497..0.499 rows=50 loops=1)
                            -> Materialize CTE page  (cost=232688..232688 rows=50) (actual time=0.496..0.496 rows=50 loops=1)
                                -> Limit: 50 row(s)  (cost=232683 rows=50) (actual time=0.482..0.487 rows=50 loops=1)
                                    -> Filter: (pm1_0.deleted_at is null)  (cost=232683 rows=2.3e+6) (actual time=0.482..0.485 rows=50 loops=1)
                                        -> Covering index lookup on pm1_0 using ix_product_latest (deleted_at=NULL) (reverse)  (cost=232683 rows=2.3e+6) (actual time=0.481..0.484 rows=50 loops=1)
                        -> Single-row index lookup on pm2_0 using PRIMARY (id=pg1_0.id)  (cost=0.913 rows=1) (actual time=575e-6..597e-6 rows=1 loops=50)
                    -> Single-row index lookup on bm1_0 using PRIMARY (id=pm2_0.brand_id)  (cost=0.252 rows=1) (actual time=515e-6..529e-6 rows=1 loops=50)
                -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pg1_0.id)  (cost=0.26 rows=4.11) (actual time=946e-6..0.00108 rows=1.52 loops=50)
```

**실행 계획 — PostgreSQL 변경 후**

```text
Sort  (cost=144937.41..144937.60 rows=75 width=67) (actual time=221.294..225.381 rows=50 loops=1)
  Sort Key: pm2_0.created_at DESC, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=144933.58..144935.08 rows=75 width=67) (actual time=221.261..225.360 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=144933.58..144933.77 rows=75 width=67) (actual time=221.253..225.341 rows=73 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 35kB
              ->  Nested Loop Left Join  (cost=144319.99..144931.24 rows=75 width=67) (actual time=221.106..225.311 rows=73 loops=1)
                    ->  Nested Loop  (cost=144319.55..144762.31 rows=50 width=59) (actual time=221.100..225.270 rows=50 loops=1)
                          ->  Nested Loop  (cost=144319.28..144747.68 rows=50 width=46) (actual time=221.093..225.226 rows=50 loops=1)
                                ->  Limit  (cost=144318.85..144324.68 rows=50 width=16) (actual time=221.065..225.161 rows=50 loops=1)
                                      ->  Gather Merge  (cost=144318.85..605539.05 rows=3953040 width=16) (actual time=215.598..219.687 rows=50 loops=1)
                                            Workers Planned: 2
                                            Workers Launched: 2
                                            ->  Sort  (cost=143318.82..148260.12 rows=1976520 width=16) (actual time=210.306..210.307 rows=42 loops=3)
                                                  Sort Key: pm1_0.created_at DESC, pm1_0.id DESC
                                                  Sort Method: top-N heapsort  Memory: 29kB
                                                  Worker 0:  Sort Method: top-N heapsort  Memory: 29kB
                                                  Worker 1:  Sort Method: top-N heapsort  Memory: 29kB
                                                  ->  Parallel Seq Scan on product pm1_0  (cost=0.00..77660.25 rows=1976520 width=16) (actual time=1.011..79.463 rows=1583248 loops=3)
                                                        Filter: (deleted_at IS NULL)
                                                        Rows Removed by Filter: 83419
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=46) (actual time=0.001..0.001 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.001..0.001 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.001..0.001 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.441 ms
JIT:
  Functions: 34
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.633 ms, Inlining 0.000 ms, Optimization 0.439 ms, Emission 8.051 ms, Total 9.123 ms
Execution Time: 232.224 ms
```

### Q02

[L02](#l02) 의 변경 후 — 전체 · 첫 페이지 (`page=0`) · `price_asc` — `/api/v1/products?sort=price_asc&page=0&size=50`

```sql
with page (id) as (
    select pm1_0.id
    from product pm1_0
    where pm1_0.deleted_at is null
    order by pm1_0.price,pm1_0.id desc
    limit 0,50)
select pm2_0.id,pm2_0.name,pm2_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from page pg1_0
join product pm2_0 on pm2_0.id=pg1_0.id
join brand bm1_0 on bm1_0.id=pm2_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm2_0.id
group by pm2_0.id,bm1_0.id
order by pm2_0.price,pm2_0.id desc
```

PostgreSQL 에서는 페이징만 다르다.

```sql
with page (id) as (
    select pm1_0.id
    from product pm1_0
    where pm1_0.deleted_at is null
    order by pm1_0.price,pm1_0.id desc
    offset 0 rows fetch first 50 rows only)
select pm2_0.id,pm2_0.name,pm2_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from page pg1_0
join product pm2_0 on pm2_0.id=pg1_0.id
join brand bm1_0 on bm1_0.id=pm2_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm2_0.id
group by pm2_0.id,bm1_0.id
order by pm2_0.price,pm2_0.id desc
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| MySQL 변경 전 (L02, 같은 데이터에서 다시 잼) | 41,103.8 (39,713.0~43,154.0) |
| MySQL 변경 후 | 680.8 (676.0~684.0) |
| PostgreSQL 변경 전 (L02) | 7,441.3 (7,146.8~7,809.0) |
| PostgreSQL 변경 후 | 185.4 (183.7~186.9) |

**실행 계획 — MySQL 변경 후**

```text
-> Sort: pm2_0.price, pm2_0.id DESC  (actual time=684..684 rows=50 loops=1)
    -> Table scan on <temporary>  (actual time=684..684 rows=50 loops=1)
        -> Aggregate using temporary table  (actual time=684..684 rows=50 loops=1)
            -> Nested loop left join  (cost=233316 rows=206) (actual time=684..684 rows=67 loops=1)
                -> Nested loop inner join  (cost=233282 rows=50) (actual time=684..684 rows=50 loops=1)
                    -> Nested loop inner join  (cost=233265 rows=50) (actual time=684..684 rows=50 loops=1)
                        -> Table scan on pg1_0  (cost=233211..233214 rows=50) (actual time=684..684 rows=50 loops=1)
                            -> Materialize CTE page  (cost=233211..233211 rows=50) (actual time=684..684 rows=50 loops=1)
                                -> Limit: 50 row(s)  (cost=233206 rows=50) (actual time=684..684 rows=50 loops=1)
                                    -> Sort: pm1_0.price, pm1_0.id DESC, limit input to 50 row(s) per chunk  (cost=233206 rows=2.3e+6) (actual time=684..684 rows=50 loops=1)
                                        -> Filter: (pm1_0.deleted_at is null)  (cost=233206 rows=2.3e+6) (actual time=0.0443..495 rows=4.75e+6 loops=1)
                                            -> Index lookup on pm1_0 using ix_product_price (deleted_at=NULL)  (cost=233206 rows=2.3e+6) (actual time=0.044..352 rows=4.75e+6 loops=1)
                        -> Single-row index lookup on pm2_0 using PRIMARY (id=pg1_0.id)  (cost=0.913 rows=1) (actual time=0.00325..0.00327 rows=1 loops=50)
                    -> Single-row index lookup on bm1_0 using PRIMARY (id=pm2_0.brand_id)  (cost=0.252 rows=1) (actual time=687e-6..704e-6 rows=1 loops=50)
                -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pg1_0.id)  (cost=0.26 rows=4.11) (actual time=983e-6..0.00106 rows=0.72 loops=50)
```

**실행 계획 — PostgreSQL 변경 후**

```text
Sort  (cost=144937.41..144937.60 rows=75 width=59) (actual time=180.992..185.460 rows=50 loops=1)
  Sort Key: pm2_0.price, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=144933.58..144935.08 rows=75 width=59) (actual time=180.960..185.440 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=144933.58..144933.77 rows=75 width=59) (actual time=180.952..185.420 rows=79 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 36kB
              ->  Nested Loop Left Join  (cost=144319.99..144931.24 rows=75 width=59) (actual time=180.737..185.393 rows=79 loops=1)
                    ->  Nested Loop  (cost=144319.55..144762.31 rows=50 width=51) (actual time=180.721..185.294 rows=50 loops=1)
                          ->  Nested Loop  (cost=144319.28..144747.68 rows=50 width=38) (actual time=180.711..185.243 rows=50 loops=1)
                                ->  Limit  (cost=144318.85..144324.68 rows=50 width=16) (actual time=180.663..185.134 rows=50 loops=1)
                                      ->  Gather Merge  (cost=144318.85..605539.05 rows=3953040 width=16) (actual time=175.180..179.649 rows=50 loops=1)
                                            Workers Planned: 2
                                            Workers Launched: 2
                                            ->  Sort  (cost=143318.82..148260.12 rows=1976520 width=16) (actual time=169.676..169.678 rows=38 loops=3)
                                                  Sort Key: pm1_0.price, pm1_0.id DESC
                                                  Sort Method: top-N heapsort  Memory: 29kB
                                                  Worker 0:  Sort Method: top-N heapsort  Memory: 28kB
                                                  Worker 1:  Sort Method: top-N heapsort  Memory: 28kB
                                                  ->  Parallel Seq Scan on product pm1_0  (cost=0.00..77660.25 rows=1976520 width=16) (actual time=1.081..102.239 rows=1583248 loops=3)
                                                        Filter: (deleted_at IS NULL)
                                                        Rows Removed by Filter: 83419
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=38) (actual time=0.001..0.001 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.001..0.001 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.002..0.002 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.456 ms
JIT:
  Functions: 34
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.729 ms, Inlining 0.000 ms, Optimization 0.512 ms, Emission 8.213 ms, Total 9.454 ms
Execution Time: 192.472 ms
```

### Q04

[L04](#l04) 의 변경 후 — 전체 · 딥 페이지 (`page=99`) · `latest` — `/api/v1/products?sort=latest&page=99&size=50`

```sql
with page (id) as (
    select pm1_0.id
    from product pm1_0
    where pm1_0.deleted_at is null
    order by pm1_0.created_at desc,pm1_0.id desc
    limit 4950,50)
select pm2_0.id,pm2_0.name,pm2_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from page pg1_0
join product pm2_0 on pm2_0.id=pg1_0.id
join brand bm1_0 on bm1_0.id=pm2_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm2_0.id
group by pm2_0.id,bm1_0.id
order by pm2_0.created_at desc,pm2_0.id desc
```

PostgreSQL 에서는 페이징만 다르다.

```sql
with page (id) as (
    select pm1_0.id
    from product pm1_0
    where pm1_0.deleted_at is null
    order by pm1_0.created_at desc,pm1_0.id desc
    offset 4950 rows fetch first 50 rows only)
select pm2_0.id,pm2_0.name,pm2_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from page pg1_0
join product pm2_0 on pm2_0.id=pg1_0.id
join brand bm1_0 on bm1_0.id=pm2_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm2_0.id
group by pm2_0.id,bm1_0.id
order by pm2_0.created_at desc,pm2_0.id desc
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| MySQL 변경 전 (L04, 같은 데이터에서 다시 잼) | 45,478.6 (44,073.0~48,059.0) |
| MySQL 변경 후 | 1.1 (1.0~1.2) |
| PostgreSQL 변경 전 (L04) | 7,556.0 (7,304.7~7,857.6) |
| PostgreSQL 변경 후 | 303.7 (301.5~306.9) |

**실행 계획 — MySQL 변경 후**

```text
-> Sort: pm2_0.created_at DESC, pm2_0.id DESC  (actual time=1.24..1.24 rows=50 loops=1)
    -> Table scan on <temporary>  (actual time=1.23..1.23 rows=50 loops=1)
        -> Aggregate using temporary table  (actual time=1.23..1.23 rows=50 loops=1)
            -> Nested loop left join  (cost=239783 rows=206) (actual time=1.08..1.19 rows=68 loops=1)
                -> Nested loop inner join  (cost=238505 rows=50) (actual time=1.08..1.15 rows=50 loops=1)
                    -> Nested loop inner join  (cost=237250 rows=50) (actual time=1.07..1.11 rows=50 loops=1)
                        -> Table scan on pg1_0  (cost=232688..232691 rows=50) (actual time=1.07..1.07 rows=50 loops=1)
                            -> Materialize CTE page  (cost=232688..232688 rows=50) (actual time=1.07..1.07 rows=50 loops=1)
                                -> Limit/Offset: 50/4950 row(s)  (cost=232683 rows=50) (actual time=1.05..1.06 rows=50 loops=1)
                                    -> Filter: (pm1_0.deleted_at is null)  (cost=232683 rows=2.3e+6) (actual time=0.656..0.967 rows=5000 loops=1)
                                        -> Covering index lookup on pm1_0 using ix_product_latest (deleted_at=NULL) (reverse)  (cost=232683 rows=2.3e+6) (actual time=0.655..0.817 rows=5000 loops=1)
                        -> Single-row index lookup on pm2_0 using PRIMARY (id=pg1_0.id)  (cost=0.911 rows=1) (actual time=737e-6..754e-6 rows=1 loops=50)
                    -> Single-row index lookup on bm1_0 using PRIMARY (id=pm2_0.brand_id)  (cost=0.25 rows=1) (actual time=587e-6..600e-6 rows=1 loops=50)
                -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pg1_0.id)  (cost=0.252 rows=4.11) (actual time=698e-6..770e-6 rows=0.72 loops=50)
```

**실행 계획 — PostgreSQL 변경 후**

```text
Sort  (cost=211173.53..211173.71 rows=75 width=67) (actual time=297.014..301.466 rows=50 loops=1)
  Sort Key: pm2_0.created_at DESC, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=211169.69..211171.19 rows=75 width=67) (actual time=296.995..301.456 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=211169.69..211169.88 rows=75 width=67) (actual time=296.989..301.441 rows=64 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 34kB
              ->  Nested Loop Left Join  (cost=210556.10..211167.36 rows=75 width=67) (actual time=296.883..301.429 rows=64 loops=1)
                    ->  Nested Loop  (cost=210555.67..210998.42 rows=50 width=59) (actual time=296.873..301.391 rows=50 loops=1)
                          ->  Nested Loop  (cost=210555.39..210983.79 rows=50 width=46) (actual time=296.867..301.354 rows=50 loops=1)
                                ->  Limit  (cost=210554.96..210560.79 rows=50 width=16) (actual time=296.836..301.291 rows=50 loops=1)
                                      ->  Gather Merge  (cost=209977.42..671197.62 rows=3953040 width=16) (actual time=291.008..295.863 rows=5000 loops=1)
                                            Workers Planned: 2
                                            Workers Launched: 2
                                            ->  Sort  (cost=208977.40..213918.70 rows=1976520 width=16) (actual time=285.872..285.932 rows=2729 loops=3)
                                                  Sort Key: pm1_0.created_at DESC, pm1_0.id DESC
                                                  Sort Method: top-N heapsort  Memory: 853kB
                                                  Worker 0:  Sort Method: top-N heapsort  Memory: 853kB
                                                  Worker 1:  Sort Method: top-N heapsort  Memory: 853kB
                                                  ->  Parallel Seq Scan on product pm1_0  (cost=0.00..77660.25 rows=1976520 width=16) (actual time=0.915..79.782 rows=1583248 loops=3)
                                                        Filter: (deleted_at IS NULL)
                                                        Rows Removed by Filter: 83419
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=46) (actual time=0.001..0.001 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.001..0.001 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.001..0.001 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.420 ms
JIT:
  Functions: 34
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.519 ms, Inlining 0.000 ms, Optimization 0.383 ms, Emission 7.694 ms, Total 8.596 ms
Execution Time: 308.099 ms
```

### Q05

[L05](#l05) 의 변경 후 — 전체 · 딥 페이지 (`page=99`) · `price_asc` — `/api/v1/products?sort=price_asc&page=99&size=50`

```sql
with page (id) as (
    select pm1_0.id
    from product pm1_0
    where pm1_0.deleted_at is null
    order by pm1_0.price,pm1_0.id desc
    limit 4950,50)
select pm2_0.id,pm2_0.name,pm2_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from page pg1_0
join product pm2_0 on pm2_0.id=pg1_0.id
join brand bm1_0 on bm1_0.id=pm2_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm2_0.id
group by pm2_0.id,bm1_0.id
order by pm2_0.price,pm2_0.id desc
```

PostgreSQL 에서는 페이징만 다르다.

```sql
with page (id) as (
    select pm1_0.id
    from product pm1_0
    where pm1_0.deleted_at is null
    order by pm1_0.price,pm1_0.id desc
    offset 4950 rows fetch first 50 rows only)
select pm2_0.id,pm2_0.name,pm2_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from page pg1_0
join product pm2_0 on pm2_0.id=pg1_0.id
join brand bm1_0 on bm1_0.id=pm2_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm2_0.id
group by pm2_0.id,bm1_0.id
order by pm2_0.price,pm2_0.id desc
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| MySQL 변경 전 (L05, 같은 데이터에서 다시 잼) | 42,912.6 (41,666.0~44,605.0) |
| MySQL 변경 후 | 683.8 (678.0~690.0) |
| PostgreSQL 변경 전 (L05) | 7,370.5 (7,078.3~7,962.0) |
| PostgreSQL 변경 후 | 195.2 (193.7~198.3) |

**실행 계획 — MySQL 변경 후**

```text
-> Sort: pm2_0.price, pm2_0.id DESC  (actual time=678..678 rows=50 loops=1)
    -> Table scan on <temporary>  (actual time=678..678 rows=50 loops=1)
        -> Aggregate using temporary table  (actual time=678..678 rows=50 loops=1)
            -> Nested loop left join  (cost=240306 rows=206) (actual time=678..678 rows=335 loops=1)
                -> Nested loop inner join  (cost=239028 rows=50) (actual time=678..678 rows=50 loops=1)
                    -> Nested loop inner join  (cost=237773 rows=50) (actual time=678..678 rows=50 loops=1)
                        -> Table scan on pg1_0  (cost=233211..233214 rows=50) (actual time=678..678 rows=50 loops=1)
                            -> Materialize CTE page  (cost=233211..233211 rows=50) (actual time=678..678 rows=50 loops=1)
                                -> Limit/Offset: 50/4950 row(s)  (cost=233206 rows=50) (actual time=678..678 rows=50 loops=1)
                                    -> Sort: pm1_0.price, pm1_0.id DESC, limit input to 5000 row(s) per chunk  (cost=233206 rows=2.3e+6) (actual time=678..678 rows=5000 loops=1)
                                        -> Filter: (pm1_0.deleted_at is null)  (cost=233206 rows=2.3e+6) (actual time=0.0311..491 rows=4.75e+6 loops=1)
                                            -> Index lookup on pm1_0 using ix_product_price (deleted_at=NULL)  (cost=233206 rows=2.3e+6) (actual time=0.0305..350 rows=4.75e+6 loops=1)
                        -> Single-row index lookup on pm2_0 using PRIMARY (id=pg1_0.id)  (cost=0.911 rows=1) (actual time=0.0028..0.00281 rows=1 loops=50)
                    -> Single-row index lookup on bm1_0 using PRIMARY (id=pm2_0.brand_id)  (cost=0.25 rows=1) (actual time=636e-6..647e-6 rows=1 loops=50)
                -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pg1_0.id)  (cost=0.252 rows=4.11) (actual time=0.00102..0.00149 rows=6.12 loops=50)
```

**실행 계획 — PostgreSQL 변경 후**

```text
Sort  (cost=211173.53..211173.71 rows=75 width=59) (actual time=191.703..196.006 rows=50 loops=1)
  Sort Key: pm2_0.price, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=211169.69..211171.19 rows=75 width=59) (actual time=191.668..195.981 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=211169.69..211169.88 rows=75 width=59) (actual time=191.662..195.965 rows=80 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 36kB
              ->  Nested Loop Left Join  (cost=210556.10..211167.36 rows=75 width=59) (actual time=191.470..195.951 rows=80 loops=1)
                    ->  Nested Loop  (cost=210555.67..210998.42 rows=50 width=51) (actual time=191.463..195.886 rows=50 loops=1)
                          ->  Nested Loop  (cost=210555.39..210983.79 rows=50 width=38) (actual time=191.457..195.840 rows=50 loops=1)
                                ->  Limit  (cost=210554.96..210560.79 rows=50 width=16) (actual time=191.422..195.733 rows=50 loops=1)
                                      ->  Gather Merge  (cost=209977.42..671197.62 rows=3953040 width=16) (actual time=185.965..190.713 rows=5000 loops=1)
                                            Workers Planned: 2
                                            Workers Launched: 2
                                            ->  Sort  (cost=208977.40..213918.70 rows=1976520 width=16) (actual time=180.641..180.717 rows=2622 loops=3)
                                                  Sort Key: pm1_0.price, pm1_0.id DESC
                                                  Sort Method: top-N heapsort  Memory: 780kB
                                                  Worker 0:  Sort Method: top-N heapsort  Memory: 780kB
                                                  Worker 1:  Sort Method: top-N heapsort  Memory: 780kB
                                                  ->  Parallel Seq Scan on product pm1_0  (cost=0.00..77660.25 rows=1976520 width=16) (actual time=1.056..102.235 rows=1583248 loops=3)
                                                        Filter: (deleted_at IS NULL)
                                                        Rows Removed by Filter: 83419
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=38) (actual time=0.002..0.002 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.001..0.001 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.001..0.001 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.426 ms
JIT:
  Functions: 34
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.566 ms, Inlining 0.000 ms, Optimization 0.416 ms, Emission 7.677 ms, Total 8.659 ms
Execution Time: 202.606 ms
```

### Q07

[L07](#l07) 의 변경 후 — 1번 브랜드 · 첫 페이지 (`page=0`) · `latest` — `/api/v1/products?sort=latest&page=0&size=50&brandId=1`

```sql
with page (id) as (
    select pm1_0.id
    from product pm1_0
    where pm1_0.deleted_at is null and pm1_0.brand_id=1
    order by pm1_0.created_at desc,pm1_0.id desc
    limit 0,50)
select pm2_0.id,pm2_0.name,pm2_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from page pg1_0
join product pm2_0 on pm2_0.id=pg1_0.id
join brand bm1_0 on bm1_0.id=pm2_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm2_0.id
group by pm2_0.id,bm1_0.id
order by pm2_0.created_at desc,pm2_0.id desc
```

PostgreSQL 에서는 페이징만 다르다.

```sql
with page (id) as (
    select pm1_0.id
    from product pm1_0
    where pm1_0.deleted_at is null and pm1_0.brand_id=1
    order by pm1_0.created_at desc,pm1_0.id desc
    offset 0 rows fetch first 50 rows only)
select pm2_0.id,pm2_0.name,pm2_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from page pg1_0
join product pm2_0 on pm2_0.id=pg1_0.id
join brand bm1_0 on bm1_0.id=pm2_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm2_0.id
group by pm2_0.id,bm1_0.id
order by pm2_0.created_at desc,pm2_0.id desc
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| MySQL 변경 전 (L07, 같은 데이터에서 다시 잼) | 2,192.2 (2,155.0~2,296.0) |
| MySQL 변경 후 | 0.6 (0.5~0.6) |
| PostgreSQL 변경 전 (L07) | 1,529.5 (1,452.0~1,799.0) |
| PostgreSQL 변경 후 | 30.3 (27.9~32.5) |

**실행 계획 — MySQL 변경 후**

```text
-> Sort: pm2_0.created_at DESC, pm2_0.id DESC  (actual time=0.641..0.642 rows=50 loops=1)
    -> Table scan on <temporary>  (actual time=0.621..0.624 rows=50 loops=1)
        -> Aggregate using temporary table  (actual time=0.62..0.62 rows=50 loops=1)
            -> Nested loop left join  (cost=95917 rows=206) (actual time=0.481..0.581 rows=77 loops=1)
                -> Nested loop inner join  (cost=95883 rows=50) (actual time=0.476..0.533 rows=50 loops=1)
                    -> Nested loop inner join  (cost=95866 rows=50) (actual time=0.473..0.522 rows=50 loops=1)
                        -> Table scan on pg1_0  (cost=95812..95815 rows=50) (actual time=0.467..0.47 rows=50 loops=1)
                            -> Materialize CTE page  (cost=95812..95812 rows=50) (actual time=0.465..0.465 rows=50 loops=1)
                                -> Limit: 50 row(s)  (cost=95807 rows=50) (actual time=0.449..0.454 rows=50 loops=1)
                                    -> Filter: (pm1_0.deleted_at is null)  (cost=95807 rows=946242) (actual time=0.448..0.452 rows=50 loops=1)
                                        -> Covering index lookup on pm1_0 using ix_product_brand_latest (brand_id=1, deleted_at=NULL) (reverse)  (cost=95807 rows=946242) (actual time=0.448..0.45 rows=50 loops=1)
                        -> Single-row index lookup on pm2_0 using PRIMARY (id=pg1_0.id)  (cost=0.913 rows=1) (actual time=935e-6..948e-6 rows=1 loops=50)
                    -> Single-row index lookup on bm1_0 using PRIMARY (id=pm2_0.brand_id)  (cost=0.252 rows=1) (actual time=107e-6..122e-6 rows=1 loops=50)
                -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pg1_0.id)  (cost=0.26 rows=4.11) (actual time=744e-6..850e-6 rows=1.06 loops=50)
```

**실행 계획 — PostgreSQL 변경 후**

```text
Sort  (cost=34536.03..34536.22 rows=75 width=67) (actual time=31.348..32.537 rows=50 loops=1)
  Sort Key: pm2_0.created_at DESC, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=34532.20..34533.70 rows=75 width=67) (actual time=31.323..32.523 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=34532.20..34532.39 rows=75 width=67) (actual time=31.320..32.509 rows=81 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 36kB
              ->  Nested Loop Left Join  (cost=33918.61..34529.86 rows=75 width=67) (actual time=31.183..32.492 rows=81 loops=1)
                    ->  Nested Loop  (cost=33918.17..34360.93 rows=50 width=59) (actual time=31.166..32.430 rows=50 loops=1)
                          ->  Nested Loop  (cost=33917.90..34346.30 rows=50 width=46) (actual time=31.159..32.402 rows=50 loops=1)
                                ->  Limit  (cost=33917.47..33923.30 rows=50 width=16) (actual time=31.126..32.320 rows=50 loops=1)
                                      ->  Gather Merge  (cost=33917.47..78994.08 rows=386344 width=16) (actual time=31.126..32.317 rows=50 loops=1)
                                            Workers Planned: 2
                                            Workers Launched: 2
                                            ->  Sort  (cost=32917.44..33400.37 rows=193172 width=16) (actual time=30.305..30.306 rows=34 loops=3)
                                                  Sort Key: pm1_0.created_at DESC, pm1_0.id DESC
                                                  Sort Method: top-N heapsort  Memory: 29kB
                                                  Worker 0:  Sort Method: top-N heapsort  Memory: 29kB
                                                  Worker 1:  Sort Method: top-N heapsort  Memory: 29kB
                                                  ->  Parallel Index Only Scan using ix_product_brand_latest on product pm1_0  (cost=0.56..26500.41 rows=193172 width=16) (actual time=0.016..14.288 rows=158159 loops=3)
                                                        Index Cond: ((brand_id = 1) AND (deleted_at IS NULL))
                                                        Heap Fetches: 0
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=46) (actual time=0.001..0.001 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.000..0.000 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.001..0.001 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.424 ms
Execution Time: 32.589 ms
```

### Q08

[L08](#l08) 의 변경 후 — 1번 브랜드 · 첫 페이지 (`page=0`) · `price_asc` — `/api/v1/products?sort=price_asc&page=0&size=50&brandId=1`

```sql
with page (id) as (
    select pm1_0.id
    from product pm1_0
    where pm1_0.deleted_at is null and pm1_0.brand_id=1
    order by pm1_0.price,pm1_0.id desc
    limit 0,50)
select pm2_0.id,pm2_0.name,pm2_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from page pg1_0
join product pm2_0 on pm2_0.id=pg1_0.id
join brand bm1_0 on bm1_0.id=pm2_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm2_0.id
group by pm2_0.id,bm1_0.id
order by pm2_0.price,pm2_0.id desc
```

PostgreSQL 에서는 페이징만 다르다.

```sql
with page (id) as (
    select pm1_0.id
    from product pm1_0
    where pm1_0.deleted_at is null and pm1_0.brand_id=1
    order by pm1_0.price,pm1_0.id desc
    offset 0 rows fetch first 50 rows only)
select pm2_0.id,pm2_0.name,pm2_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from page pg1_0
join product pm2_0 on pm2_0.id=pg1_0.id
join brand bm1_0 on bm1_0.id=pm2_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm2_0.id
group by pm2_0.id,bm1_0.id
order by pm2_0.price,pm2_0.id desc
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| MySQL 변경 전 (L08, 같은 데이터에서 다시 잼) | 2,019.4 (1,959.0~2,103.0) |
| MySQL 변경 후 | 76.7 (75.5~77.4) |
| PostgreSQL 변경 전 (L08) | 1,511.4 (1,432.2~1,660.5) |
| PostgreSQL 변경 후 | 22.5 (21.4~23.8) |

**실행 계획 — MySQL 변경 후**

```text
-> Sort: pm2_0.price, pm2_0.id DESC  (actual time=77.4..77.4 rows=50 loops=1)
    -> Table scan on <temporary>  (actual time=77.3..77.3 rows=50 loops=1)
        -> Aggregate using temporary table  (actual time=77.3..77.3 rows=50 loops=1)
            -> Nested loop left join  (cost=135740 rows=206) (actual time=77.1..77.3 rows=96 loops=1)
                -> Nested loop inner join  (cost=135707 rows=50) (actual time=77.1..77.2 rows=50 loops=1)
                    -> Nested loop inner join  (cost=135689 rows=50) (actual time=77.1..77.2 rows=50 loops=1)
                        -> Table scan on pg1_0  (cost=135636..135639 rows=50) (actual time=77.1..77.1 rows=50 loops=1)
                            -> Materialize CTE page  (cost=135636..135636 rows=50) (actual time=77.1..77.1 rows=50 loops=1)
                                -> Limit: 50 row(s)  (cost=135631 rows=50) (actual time=77.1..77.1 rows=50 loops=1)
                                    -> Sort: pm1_0.price, pm1_0.id DESC, limit input to 50 row(s) per chunk  (cost=135631 rows=1.3e+6) (actual time=77.1..77.1 rows=50 loops=1)
                                        -> Filter: (pm1_0.deleted_at is null)  (cost=135631 rows=1.3e+6) (actual time=0.0287..57.4 rows=473653 loops=1)
                                            -> Index lookup on pm1_0 using ix_product_brand_price (brand_id=1, deleted_at=NULL)  (cost=135631 rows=1.3e+6) (actual time=0.0283..42.3 rows=473653 loops=1)
                        -> Single-row index lookup on pm2_0 using PRIMARY (id=pg1_0.id)  (cost=0.913 rows=1) (actual time=0.00254..0.00256 rows=1 loops=50)
                    -> Single-row index lookup on bm1_0 using PRIMARY (id=pm2_0.brand_id)  (cost=0.252 rows=1) (actual time=163e-6..175e-6 rows=1 loops=50)
                -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pg1_0.id)  (cost=0.26 rows=4.11) (actual time=0.00131..0.00142 rows=1.3 loops=50)
```

**실행 계획 — PostgreSQL 변경 후**

```text
Sort  (cost=29371.91..29372.10 rows=75 width=59) (actual time=22.963..23.771 rows=50 loops=1)
  Sort Key: pm2_0.price, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=29368.07..29369.57 rows=75 width=59) (actual time=22.938..23.759 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=29368.07..29368.26 rows=75 width=59) (actual time=22.933..23.742 rows=95 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 38kB
              ->  Nested Loop Left Join  (cost=28754.48..29365.74 rows=75 width=59) (actual time=22.540..23.722 rows=95 loops=1)
                    ->  Nested Loop  (cost=28754.05..29196.80 rows=50 width=51) (actual time=22.522..23.560 rows=50 loops=1)
                          ->  Nested Loop  (cost=28753.77..29182.18 rows=50 width=38) (actual time=22.517..23.533 rows=50 loops=1)
                                ->  Limit  (cost=28753.34..28759.18 rows=50 width=16) (actual time=22.491..23.303 rows=50 loops=1)
                                      ->  Gather Merge  (cost=28753.34..73829.96 rows=386344 width=16) (actual time=22.489..23.301 rows=50 loops=1)
                                            Workers Planned: 2
                                            Workers Launched: 2
                                            ->  Sort  (cost=27753.32..28236.25 rows=193172 width=16) (actual time=21.711..21.712 rows=50 loops=3)
                                                  Sort Key: pm1_0.price, pm1_0.id DESC
                                                  Sort Method: top-N heapsort  Memory: 29kB
                                                  Worker 0:  Sort Method: top-N heapsort  Memory: 29kB
                                                  Worker 1:  Sort Method: top-N heapsort  Memory: 29kB
                                                  ->  Parallel Index Only Scan using ix_product_brand_price on product pm1_0  (cost=0.43..21336.28 rows=193172 width=16) (actual time=0.010..14.028 rows=158159 loops=3)
                                                        Index Cond: ((brand_id = 1) AND (deleted_at IS NULL))
                                                        Heap Fetches: 0
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=38) (actual time=0.004..0.004 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.000..0.000 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.003..0.003 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.439 ms
Execution Time: 23.818 ms
```

### Q10

[L10](#l10) 의 변경 후 — 1번 브랜드 · 딥 페이지 (`page=99`) · `latest` — `/api/v1/products?sort=latest&page=99&size=50&brandId=1`

```sql
with page (id) as (
    select pm1_0.id
    from product pm1_0
    where pm1_0.deleted_at is null and pm1_0.brand_id=1
    order by pm1_0.created_at desc,pm1_0.id desc
    limit 4950,50)
select pm2_0.id,pm2_0.name,pm2_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from page pg1_0
join product pm2_0 on pm2_0.id=pg1_0.id
join brand bm1_0 on bm1_0.id=pm2_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm2_0.id
group by pm2_0.id,bm1_0.id
order by pm2_0.created_at desc,pm2_0.id desc
```

PostgreSQL 에서는 페이징만 다르다.

```sql
with page (id) as (
    select pm1_0.id
    from product pm1_0
    where pm1_0.deleted_at is null and pm1_0.brand_id=1
    order by pm1_0.created_at desc,pm1_0.id desc
    offset 4950 rows fetch first 50 rows only)
select pm2_0.id,pm2_0.name,pm2_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from page pg1_0
join product pm2_0 on pm2_0.id=pg1_0.id
join brand bm1_0 on bm1_0.id=pm2_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm2_0.id
group by pm2_0.id,bm1_0.id
order by pm2_0.created_at desc,pm2_0.id desc
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| MySQL 변경 전 (L10, 같은 데이터에서 다시 잼) | 2,163.8 (2,102.0~2,264.0) |
| MySQL 변경 후 | 1.3 (1.1~1.6) |
| PostgreSQL 변경 전 (L10) | 1,467.6 (1,448.7~1,508.1) |
| PostgreSQL 변경 후 | 41.2 (38.9~44.8) |

**실행 계획 — MySQL 변경 후**

```text
-> Sort: pm2_0.created_at DESC, pm2_0.id DESC  (actual time=1.21..1.21 rows=50 loops=1)
    -> Table scan on <temporary>  (actual time=1.19..1.2 rows=50 loops=1)
        -> Aggregate using temporary table  (actual time=1.19..1.19 rows=50 loops=1)
            -> Nested loop left join  (cost=102908 rows=206) (actual time=1.09..1.17 rows=59 loops=1)
                -> Nested loop inner join  (cost=101630 rows=50) (actual time=1.09..1.13 rows=50 loops=1)
                    -> Nested loop inner join  (cost=100375 rows=50) (actual time=1.09..1.12 rows=50 loops=1)
                        -> Table scan on pg1_0  (cost=95812..95815 rows=50) (actual time=1.07..1.07 rows=50 loops=1)
                            -> Materialize CTE page  (cost=95812..95812 rows=50) (actual time=1.06..1.06 rows=50 loops=1)
                                -> Limit/Offset: 50/4950 row(s)  (cost=95807 rows=50) (actual time=1.05..1.06 rows=50 loops=1)
                                    -> Filter: (pm1_0.deleted_at is null)  (cost=95807 rows=946242) (actual time=0.423..0.977 rows=5000 loops=1)
                                        -> Covering index lookup on pm1_0 using ix_product_brand_latest (brand_id=1, deleted_at=NULL) (reverse)  (cost=95807 rows=946242) (actual time=0.423..0.829 rows=5000 loops=1)
                        -> Single-row index lookup on pm2_0 using PRIMARY (id=pg1_0.id)  (cost=0.911 rows=1) (actual time=911e-6..928e-6 rows=1 loops=50)
                    -> Single-row index lookup on bm1_0 using PRIMARY (id=pm2_0.brand_id)  (cost=0.25 rows=1) (actual time=92.5e-6..111e-6 rows=1 loops=50)
                -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pg1_0.id)  (cost=0.252 rows=4.11) (actual time=623e-6..662e-6 rows=0.42 loops=50)
```

**실행 계획 — PostgreSQL 변경 후**

```text
Sort  (cost=41530.61..41530.80 rows=75 width=67) (actual time=41.408..42.389 rows=50 loops=1)
  Sort Key: pm2_0.created_at DESC, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=41526.77..41528.27 rows=75 width=67) (actual time=41.388..42.378 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=41526.77..41526.96 rows=75 width=67) (actual time=41.386..42.366 rows=64 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 34kB
              ->  Nested Loop Left Join  (cost=40913.18..41524.44 rows=75 width=67) (actual time=41.278..42.353 rows=64 loops=1)
                    ->  Nested Loop  (cost=40912.75..41355.50 rows=50 width=59) (actual time=41.274..42.314 rows=50 loops=1)
                          ->  Nested Loop  (cost=40912.47..41340.88 rows=50 width=46) (actual time=41.270..42.291 rows=50 loops=1)
                                ->  Limit  (cost=40912.04..40917.88 rows=50 width=16) (actual time=41.252..42.235 rows=50 loops=1)
                                      ->  Gather Merge  (cost=40334.50..85411.12 rows=386344 width=16) (actual time=40.802..42.148 rows=5000 loops=1)
                                            Workers Planned: 2
                                            Workers Launched: 2
                                            ->  Sort  (cost=39334.48..39817.41 rows=193172 width=16) (actual time=39.691..39.752 rows=2703 loops=3)
                                                  Sort Key: pm1_0.created_at DESC, pm1_0.id DESC
                                                  Sort Method: top-N heapsort  Memory: 853kB
                                                  Worker 0:  Sort Method: top-N heapsort  Memory: 853kB
                                                  Worker 1:  Sort Method: top-N heapsort  Memory: 853kB
                                                  ->  Parallel Index Only Scan using ix_product_brand_latest on product pm1_0  (cost=0.56..26500.41 rows=193172 width=16) (actual time=0.014..14.395 rows=158159 loops=3)
                                                        Index Cond: ((brand_id = 1) AND (deleted_at IS NULL))
                                                        Heap Fetches: 0
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=46) (actual time=0.001..0.001 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.000..0.000 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.001..0.001 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.435 ms
Execution Time: 42.456 ms
```

### Q11

[L11](#l11) 의 변경 후 — 1번 브랜드 · 딥 페이지 (`page=99`) · `price_asc` — `/api/v1/products?sort=price_asc&page=99&size=50&brandId=1`

```sql
with page (id) as (
    select pm1_0.id
    from product pm1_0
    where pm1_0.deleted_at is null and pm1_0.brand_id=1
    order by pm1_0.price,pm1_0.id desc
    limit 4950,50)
select pm2_0.id,pm2_0.name,pm2_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from page pg1_0
join product pm2_0 on pm2_0.id=pg1_0.id
join brand bm1_0 on bm1_0.id=pm2_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm2_0.id
group by pm2_0.id,bm1_0.id
order by pm2_0.price,pm2_0.id desc
```

PostgreSQL 에서는 페이징만 다르다.

```sql
with page (id) as (
    select pm1_0.id
    from product pm1_0
    where pm1_0.deleted_at is null and pm1_0.brand_id=1
    order by pm1_0.price,pm1_0.id desc
    offset 4950 rows fetch first 50 rows only)
select pm2_0.id,pm2_0.name,pm2_0.price,bm1_0.id,bm1_0.name,count(plm1_0.id)
from page pg1_0
join product pm2_0 on pm2_0.id=pg1_0.id
join brand bm1_0 on bm1_0.id=pm2_0.brand_id
left join product_like plm1_0 on plm1_0.product_id=pm2_0.id
group by pm2_0.id,bm1_0.id
order by pm2_0.price,pm2_0.id desc
```

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| MySQL 변경 전 (L11, 같은 데이터에서 다시 잼) | 2,047.8 (2,011.0~2,085.0) |
| MySQL 변경 후 | 93.5 (91.7~96.5) |
| PostgreSQL 변경 전 (L11) | 1,520.4 (1,454.9~1,623.6) |
| PostgreSQL 변경 후 | 25.6 (23.0~30.4) |

**실행 계획 — MySQL 변경 후**

```text
-> Sort: pm2_0.price, pm2_0.id DESC  (actual time=94.3..94.3 rows=50 loops=1)
    -> Table scan on <temporary>  (actual time=94.3..94.3 rows=50 loops=1)
        -> Aggregate using temporary table  (actual time=94.3..94.3 rows=50 loops=1)
            -> Nested loop left join  (cost=142731 rows=206) (actual time=94..94.2 rows=68 loops=1)
                -> Nested loop inner join  (cost=141453 rows=50) (actual time=94..94.2 rows=50 loops=1)
                    -> Nested loop inner join  (cost=140198 rows=50) (actual time=94..94.2 rows=50 loops=1)
                        -> Table scan on pg1_0  (cost=135636..135639 rows=50) (actual time=94..94 rows=50 loops=1)
                            -> Materialize CTE page  (cost=135636..135636 rows=50) (actual time=94..94 rows=50 loops=1)
                                -> Limit/Offset: 50/4950 row(s)  (cost=135631 rows=50) (actual time=94..94 rows=50 loops=1)
                                    -> Sort: pm1_0.price, pm1_0.id DESC, limit input to 5000 row(s) per chunk  (cost=135631 rows=1.3e+6) (actual time=93.8..93.9 rows=5000 loops=1)
                                        -> Filter: (pm1_0.deleted_at is null)  (cost=135631 rows=1.3e+6) (actual time=0.0315..58.6 rows=473653 loops=1)
                                            -> Index lookup on pm1_0 using ix_product_brand_price (brand_id=1, deleted_at=NULL)  (cost=135631 rows=1.3e+6) (actual time=0.031..44.3 rows=473653 loops=1)
                        -> Single-row index lookup on pm2_0 using PRIMARY (id=pg1_0.id)  (cost=0.911 rows=1) (actual time=0.00262..0.00263 rows=1 loops=50)
                    -> Single-row index lookup on bm1_0 using PRIMARY (id=pm2_0.brand_id)  (cost=0.25 rows=1) (actual time=152e-6..163e-6 rows=1 loops=50)
                -> Covering index lookup on plm1_0 using ix_product_like_product (product_id=pg1_0.id)  (cost=0.252 rows=4.11) (actual time=0.00119..0.00124 rows=0.56 loops=50)
```

**실행 계획 — PostgreSQL 변경 후**

```text
Sort  (cost=36366.48..36366.67 rows=75 width=59) (actual time=22.049..23.002 rows=50 loops=1)
  Sort Key: pm2_0.price, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=36362.65..36364.15 rows=75 width=59) (actual time=22.024..22.991 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=36362.65..36362.84 rows=75 width=59) (actual time=22.021..22.976 rows=155 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 46kB
              ->  Nested Loop Left Join  (cost=35749.06..36360.31 rows=75 width=59) (actual time=21.745..22.957 rows=155 loops=1)
                    ->  Nested Loop  (cost=35748.62..36191.38 rows=50 width=51) (actual time=21.735..22.860 rows=50 loops=1)
                          ->  Nested Loop  (cost=35748.35..36176.75 rows=50 width=38) (actual time=21.732..22.837 rows=50 loops=1)
                                ->  Limit  (cost=35747.92..35753.75 rows=50 width=16) (actual time=21.711..22.666 rows=50 loops=1)
                                      ->  Gather Merge  (cost=35170.38..80246.99 rows=386344 width=16) (actual time=21.278..22.584 rows=5000 loops=1)
                                            Workers Planned: 2
                                            Workers Launched: 2
                                            ->  Sort  (cost=34170.35..34653.28 rows=193172 width=16) (actual time=20.400..20.461 rows=2682 loops=3)
                                                  Sort Key: pm1_0.price, pm1_0.id DESC
                                                  Sort Method: top-N heapsort  Memory: 781kB
                                                  Worker 0:  Sort Method: top-N heapsort  Memory: 636kB
                                                  Worker 1:  Sort Method: top-N heapsort  Memory: 635kB
                                                  ->  Parallel Index Only Scan using ix_product_brand_price on product pm1_0  (cost=0.43..21336.28 rows=193172 width=16) (actual time=0.010..12.442 rows=158159 loops=3)
                                                        Index Cond: ((brand_id = 1) AND (deleted_at IS NULL))
                                                        Heap Fetches: 0
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=38) (actual time=0.003..0.003 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.000..0.000 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.002..0.002 rows=2 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.418 ms
Execution Time: 23.068 ms
```

### 8-5. 분석

**공통: 집계가 50건으로 줄었다**

- 변경 전에는 475만(전체) / 47만(1번 브랜드) 그룹을 집계했다. PostgreSQL L01 은 집계 전 정렬이 `external merge Disk: 795376kB` 였다.
- 변경 후에는 두 DB 모두 50 그룹이다.
  - `product` PK, `brand` PK, `ix_product_like_product` 조회가 각각 50회(`loops=50`)다.
  - 바깥 집계·정렬은 1ms 안팎이다 (MySQL 50행 임시 테이블, PostgreSQL 메모리 quicksort 32~46kB).
- 그래서 남은 시간은 거의 전부 CTE 안쪽, 페이지의 id 50개 고르기다. 이 부분은 DB·정렬에 따라 다르다.

**MySQL: `latest` 는 인덱스 순서로 읽다가 멈추고, `price_asc` 는 범위 전체를 정렬한다**

- `latest` (Q01·Q04·Q07·Q10): 0.6~1.3ms.
  - `Covering index lookup on pm1_0 using ix_product_latest (deleted_at=NULL) (reverse)` (브랜드는 `ix_product_brand_latest (brand_id=1, deleted_at=NULL) (reverse)`) 로 역방향으로 읽다가 50건(딥 페이지 5,000건)에서 멈춘다.
  - MySQL 은 `deleted_at IS NULL` 을 `deleted_at=NULL` 인덱스 조회로 처리한다. 그래서 그 뒤 `(created_at, id)` 순서를 정렬에 그대로 쓴다.
- `price_asc` (Q02·Q05·Q08·Q11): 전체 680ms 안팎, 1번 브랜드 77~94ms.
  - `ix_product_price (deleted_at=NULL)` / `ix_product_brand_price (brand_id=1, deleted_at=NULL)` 로 범위 전체(475만 / 47만 건)를 읽은 뒤 `Sort: pm1_0.price, pm1_0.id DESC, limit input to 50 row(s) per chunk` 를 한다.
  - 정렬이 `price asc, id desc` 로 방향이 섞여 있다. 인덱스 `(…, price, id)` 는 모두 오름차순이라 정방향·역방향 어느 쪽으로 읽어도 이 순서가 나오지 않는다.
  - 정렬이 상위 N 건만 유지하므로 첫 페이지와 딥 페이지 차이는 작다 (Q02 681ms, Q05 684ms).
- 변경 전(같은 데이터에서 다시 잰 값)은 7-4 와 같은 구조다. `brand` 풀스캔 → 브랜드마다 `product` 인덱스 조회 → 475만 그룹 임시 테이블 집계. 다만 4-1 의 "인덱스 적용 후" 보다 짧다 (전체 60~66초 → 41~45초, 1번 브랜드 2.95~3.2초 → 2.0~2.2초). 데이터를 다시 만든 영향으로 보이지만 원인은 확인하지 않았다.

**PostgreSQL: 두 정렬 모두 범위 전체를 정렬한다**

- 전체(Q01·Q02·Q04·Q05):
  - 정렬 인덱스(`ix_product_latest`, `ix_product_price`)를 쓰지 않는다.
  - `product` 병렬 풀스캔(475만 건) 뒤 top-N heapsort 를 한다.
- 1번 브랜드(Q07·Q08·Q10·Q11):
  - `ix_product_brand_*` 를 Index Only Scan 하지만, 범위 47만 건을 다 읽은 뒤 top-N heapsort 를 한다.
- 딥 페이지가 첫 페이지보다 느린 이유: top-N 이 유지하는 행이 50건에서 5,000건(offset + 50)으로 늘어난다 (heapsort 메모리 29kB → 853kB). 예: Q01 227ms, Q04 304ms.
- `latest` 도 인덱스 순서를 못 쓰는 이유:
  - 인덱스는 `(deleted_at, created_at, id)` 인데, 맨 앞 `deleted_at` 조건이 `IS NULL` 이다.
  - PostgreSQL 은 `=` 조건이 걸린 컬럼만 정렬 키에서 상수로 빼 준다. 그래서 이 인덱스에서 `(created_at, id)` 순서를 바로 얻을 수 있다고 보지 않는다. MySQL 과 갈리는 지점이다.
  - 브랜드 인덱스 `(brand_id, deleted_at, created_at, id)` 도 `brand_id = 1` 은 빠지지만, 가운데 `deleted_at` 에서 같은 이유로 막힌다.
- `price_asc` 는 여기에 MySQL 과 같은 정렬 방향 문제가 겹친다.
- 참고 (1회 실행이라 표에는 넣지 않음):
  - Q04 의 CTE 안쪽 쿼리만 떼어 `order by deleted_at desc, created_at desc, id desc` 로 바꿔 `EXPLAIN ANALYZE` 하면 `Index Only Scan Backward using ix_product_latest` 로 5,000건만 읽고 끝났다 (`Execution Time` 2.1ms).
  - 원래 정렬 그대로는 병렬 풀스캔 + 정렬로 543.6ms 였다.

**확인하지 않은 것**

- PostgreSQL: 앱은 값을 바인딩 변수로 보낸다. 같은 문장이 5번을 넘으면 pgJDBC 가 서버 측 prepared statement 로 바꾼다 (이번 로그에서도 `execute S_4` 로 보였다). PostgreSQL 이 generic plan 을 고르면 앱의 실행 계획은 여기 붙인 것과 다를 수 있다. 2장과 같은 한계다.
- MySQL 변경 전 시간이 4-1 보다 짧아진 원인.

### 8-6. 다음 단계

- CTE 안쪽이 정렬 인덱스 순서로 읽다가 멈추게 하면 남은 시간도 줄일 수 있다. MySQL `latest` 는 이미 그렇게 된다 (1ms 안팎). 정해야 할 것은 두 가지다.
  - `price_asc` 동률 방향 (두 DB 공통): 인덱스를 `(…, price, id desc)` 로 바꿀지, 동률 정책(ASM-08)을 바꿀지.
  - PostgreSQL 의 `IS NULL` 정렬 문제: 쿼리에서 정렬 키에 `deleted_at` 을 넣을지, 인덱스 컬럼 순서를 바꿀지(예: `(created_at, id, deleted_at)`), PostgreSQL 부분 인덱스를 쓸지. MySQL 에는 영향이 없어야 한다.
    - PostgreSQL 은 부분 인덱스로 적용했다 → [9장](#9-postgresql-부분-인덱스-적용-후).
- `likes_desc` 는 7-5 대로 따로 정한다.

## 9. PostgreSQL 부분 인덱스 적용 후

8-5 의 PostgreSQL 문제(`deleted_at IS NULL` 뒤의 인덱스 순서를 정렬에 못 씀, `price_asc` 정렬 방향)를 부분 인덱스로 풀고, PostgreSQL 에서 Q 케이스를 다시 측정한다. MySQL 은 바꾸지 않았다.

### 9-1. 변경 내용

PostgreSQL `product` 에 부분 인덱스 4개를 DB 에 직접 만들고 `ANALYZE product` 를 했다.

```sql
-- Q01·Q04: 전체 · latest  (order by created_at desc, id desc)
CREATE INDEX ix_product_latest_active ON product (created_at, id) WHERE deleted_at IS NULL;
-- Q02·Q05: 전체 · price_asc  (order by price, id desc)
CREATE INDEX ix_product_price_active ON product (price, id DESC) WHERE deleted_at IS NULL;
-- Q07·Q10: 브랜드 · latest  (where brand_id = ? order by created_at desc, id desc)
CREATE INDEX ix_product_brand_latest_active ON product (brand_id, created_at, id) WHERE deleted_at IS NULL;
-- Q08·Q11: 브랜드 · price_asc  (where brand_id = ? order by price, id desc)
CREATE INDEX ix_product_brand_price_active ON product (brand_id, price, id DESC) WHERE deleted_at IS NULL;
```

- `deleted_at IS NULL` 을 인덱스 컬럼이 아니라 인덱스 조건(`WHERE`)으로 옮겼다.
  - 삭제되지 않은 상품만 인덱스에 들어간다. 쿼리의 `WHERE` 에 같은 조건이 있어 플래너가 이 인덱스를 쓸 수 있다.
  - 첫 컬럼이 정렬 키(브랜드는 `=` 조건인 `brand_id`) 가 되어, 인덱스 순서로 읽다가 필요한 만큼에서 멈출 수 있다.
- `price_asc` 는 정렬이 `price asc, id desc` 라 인덱스도 `id DESC` 로 방향을 맞췄다.
- JPA 엔티티에는 넣지 않았다. `@Index` 로는 `WHERE` 를 쓸 수 없고, PostgreSQL 측정에만 쓴다.
  - `local-pg` 프로필의 `ddl-auto: update` 는 엔티티에 없는 인덱스를 지우지 않아 앱을 다시 띄워도 남는다. `seed-pg.sql` 의 `TRUNCATE` 도 인덱스를 지우지 않는다. DB 볼륨을 새로 만들면 다시 만들어야 한다.
- 쿼리·데이터·기존 인덱스(`ix_product_*` 4개)는 그대로다.

| 인덱스 | 크기 | 같은 용도의 기존 인덱스 |
|---|---|---|
| `ix_product_latest_active` | 143 MB | `ix_product_latest` 201 MB |
| `ix_product_price_active` | 143 MB | `ix_product_price` 318 MB |
| `ix_product_brand_latest_active` | 184 MB | `ix_product_brand_latest` 420 MB |
| `ix_product_brand_price_active` | 184 MB | `ix_product_brand_price` 311 MB |

### 9-2. 측정 방법

- 8-2 와 같다 (워밍업 1회 + 측정 5회, 순차 실행, 맨 위 노드 `actual time` 끝 값). SQL 은 8-4 의 PostgreSQL SQL 그대로다.
  ```
  python3 docs/week4/bench_pg.py pg-partial Q01 Q02 Q04 Q05 Q07 Q08 Q10 Q11
  ```
  원본 결과는 `docs/week4/results/pg-partial/` 에 남는다.
- "부분 인덱스 전" 은 8-3 의 PostgreSQL "변경 후" 값(`results/pg-cte/`)이다. 데이터·설정은 같다.

### 9-3. 결과 요약 (평균, ms)

| ID | 대응 | 브랜드 | 페이지 | 정렬 | 부분 인덱스 전 (8장) | 부분 인덱스 후 | CTE 안에서 쓴 인덱스 |
|---|---|---|---|---|---|---|---|
| [Q01](#q01-부분-인덱스) | [L01](#l01) | 전체 | 첫 페이지 | `latest` | 227.2 (225.4~229.2) | 0.2 (0.2~0.2) | `ix_product_latest_active` (역방향) |
| [Q02](#q02-부분-인덱스) | [L02](#l02) | 전체 | 첫 페이지 | `price_asc` | 185.4 (183.7~186.9) | 0.2 (0.2~0.2) | `ix_product_price_active` |
| [Q04](#q04-부분-인덱스) | [L04](#l04) | 전체 | 딥 페이지 | `latest` | 303.7 (301.5~306.9) | 0.4 (0.4~0.5) | `ix_product_latest_active` (역방향) |
| [Q05](#q05-부분-인덱스) | [L05](#l05) | 전체 | 딥 페이지 | `price_asc` | 195.2 (193.7~198.3) | 0.5 (0.5~0.6) | `ix_product_price_active` |
| [Q07](#q07-부분-인덱스) | [L07](#l07) | 1번 브랜드 | 첫 페이지 | `latest` | 30.3 (27.9~32.5) | 0.2 (0.2~0.2) | `ix_product_brand_latest_active` (역방향) |
| [Q08](#q08-부분-인덱스) | [L08](#l08) | 1번 브랜드 | 첫 페이지 | `price_asc` | 22.5 (21.4~23.8) | 0.3 (0.3~0.3) | `ix_product_brand_price_active` |
| [Q10](#q10-부분-인덱스) | [L10](#l10) | 1번 브랜드 | 딥 페이지 | `latest` | 41.2 (38.9~44.8) | 0.5 (0.4~0.5) | `ix_product_brand_latest_active` (역방향) |
| [Q11](#q11-부분-인덱스) | [L11](#l11) | 1번 브랜드 | 딥 페이지 | `price_asc` | 25.6 (23.0~30.4) | 0.6 (0.6~0.6) | `ix_product_brand_price_active` |

### 9-4. 분석

- 8개 모두 CTE 안쪽이 부분 인덱스 `Index Only Scan` 으로 바뀌었다 (`latest` 는 `Backward`).
  - 8장의 `Parallel Seq Scan` / `Index Only Scan` 범위 전체 읽기 + top-N heapsort 와 `Gather Merge` 가 없어졌다.
  - 첫 페이지는 50건, 딥 페이지는 5,000건(offset + 50)을 읽고 멈춘다. `Heap Fetches: 0` 이라 테이블은 읽지 않는다.
  - 플래너는 8개 모두 기존 `ix_product_*` 대신 부분 인덱스를 골랐다.
- 그래서 남은 시간은 CTE 바깥(50건의 `product` PK 조회, `brand` 조인, `product_like` 집계, 다시 정렬)과 딥 페이지의 인덱스 5,000건 읽기다. 8개 모두 1ms 안이다.
  - 첫 페이지 0.2~0.3ms, 딥 페이지 0.4~0.6ms.
  - 전체(475만)와 1번 브랜드(47만)의 차이가 없어졌다. 읽는 양이 대상 범위가 아니라 페이지 위치로 정해지기 때문이다.
- `price_asc` 도 `latest` 와 같아졌다. 인덱스의 `id DESC` 가 정렬 `price asc, id desc` 와 같은 방향이라, 정방향으로 읽으면 그 순서가 그대로 나온다.

**확인하지 않은 것**

- 앱의 바인딩 변수 실행: 8-5 와 같은 한계다. 특히 generic plan 에서는 offset·limit 값을 모르는 채로 계획을 세우므로, 부분 인덱스를 고르는지 따로 확인해야 한다.
- count 쿼리(C01·C02): 다시 재지 않았다. 부분 인덱스가 작아 Index Only Scan 이 빨라질 수 있다.
- 쓰기 비용: `product` 보조 인덱스가 4개에서 8개로 늘었다. 기존 `ix_product_*` 4개를 지울지는 정하지 않았다.
- MySQL `price_asc` (Q02·Q05·Q08·Q11, 77~684ms): 이번 단계에서 바꾸지 않았다.

### 9-5. 케이스별 상세

#### Q01 (부분 인덱스)

[L01](#l01) · [Q01](#q01) — 전체 · 첫 페이지 · `latest`. SQL 은 [Q01](#q01) 의 PostgreSQL SQL 과 같다.

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| PostgreSQL 부분 인덱스 전 (8장) | 227.2 (225.4~229.2) |
| PostgreSQL 부분 인덱스 후 | 0.2 (0.2~0.2) |

```text
Sort  (cost=614.68..614.87 rows=75 width=67) (actual time=0.158..0.160 rows=50 loops=1)
  Sort Key: pm2_0.created_at DESC, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=610.85..612.35 rows=75 width=67) (actual time=0.131..0.144 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=610.85..611.04 rows=75 width=67) (actual time=0.128..0.130 rows=73 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 35kB
              ->  Nested Loop Left Join  (cost=1.57..608.51 rows=75 width=67) (actual time=0.022..0.117 rows=73 loops=1)
                    ->  Nested Loop  (cost=1.14..439.58 rows=50 width=59) (actual time=0.019..0.085 rows=50 loops=1)
                          ->  Nested Loop  (cost=0.86..424.95 rows=50 width=46) (actual time=0.013..0.048 rows=50 loops=1)
                                ->  Limit  (cost=0.43..1.95 rows=50 width=16) (actual time=0.007..0.012 rows=50 loops=1)
                                      ->  Index Only Scan Backward using ix_product_latest_active on product pm1_0  (cost=0.43..144306.33 rows=4749460 width=16) (actual time=0.007..0.009 rows=50 loops=1)
                                            Heap Fetches: 0
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=46) (actual time=0.001..0.001 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.001..0.001 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.000..0.000 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.515 ms
Execution Time: 0.200 ms
```

#### Q02 (부분 인덱스)

[L02](#l02) · [Q02](#q02) — 전체 · 첫 페이지 · `price_asc`. SQL 은 [Q02](#q02) 의 PostgreSQL SQL 과 같다.

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| PostgreSQL 부분 인덱스 전 (8장) | 185.4 (183.7~186.9) |
| PostgreSQL 부분 인덱스 후 | 0.2 (0.2~0.2) |

```text
Sort  (cost=614.69..614.87 rows=75 width=59) (actual time=0.212..0.214 rows=50 loops=1)
  Sort Key: pm2_0.price, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=610.85..612.35 rows=75 width=59) (actual time=0.191..0.202 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=610.85..611.04 rows=75 width=59) (actual time=0.189..0.191 rows=79 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 36kB
              ->  Nested Loop Left Join  (cost=1.57..608.51 rows=75 width=59) (actual time=0.022..0.178 rows=79 loops=1)
                    ->  Nested Loop  (cost=1.14..439.58 rows=50 width=51) (actual time=0.020..0.127 rows=50 loops=1)
                          ->  Nested Loop  (cost=0.86..424.95 rows=50 width=38) (actual time=0.017..0.098 rows=50 loops=1)
                                ->  Limit  (cost=0.43..1.95 rows=50 width=16) (actual time=0.008..0.012 rows=50 loops=1)
                                      ->  Index Only Scan using ix_product_price_active on product pm1_0  (cost=0.43..144406.33 rows=4749460 width=16) (actual time=0.008..0.010 rows=50 loops=1)
                                            Heap Fetches: 0
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=38) (actual time=0.002..0.002 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.000..0.000 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.001..0.001 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.479 ms
Execution Time: 0.248 ms
```

#### Q04 (부분 인덱스)

[L04](#l04) · [Q04](#q04) — 전체 · 딥 페이지 · `latest`. SQL 은 [Q04](#q04) 의 PostgreSQL SQL 과 같다.

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| PostgreSQL 부분 인덱스 전 (8장) | 303.7 (301.5~306.9) |
| PostgreSQL 부분 인덱스 후 | 0.4 (0.4~0.5) |

```text
Sort  (cost=765.08..765.27 rows=75 width=67) (actual time=0.440..0.441 rows=50 loops=1)
  Sort Key: pm2_0.created_at DESC, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=761.25..762.75 rows=75 width=67) (actual time=0.413..0.423 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=761.25..761.44 rows=75 width=67) (actual time=0.410..0.412 rows=64 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 34kB
              ->  Nested Loop Left Join  (cost=151.97..758.91 rows=75 width=67) (actual time=0.313..0.401 rows=64 loops=1)
                    ->  Nested Loop  (cost=151.54..589.98 rows=50 width=59) (actual time=0.308..0.371 rows=50 loops=1)
                          ->  Nested Loop  (cost=151.26..575.35 rows=50 width=46) (actual time=0.304..0.335 rows=50 loops=1)
                                ->  Limit  (cost=150.83..152.35 rows=50 width=16) (actual time=0.291..0.295 rows=50 loops=1)
                                      ->  Index Only Scan Backward using ix_product_latest_active on product pm1_0  (cost=0.43..144306.33 rows=4749460 width=16) (actual time=0.007..0.217 rows=5000 loops=1)
                                            Heap Fetches: 0
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=46) (actual time=0.001..0.001 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.001..0.001 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.000..0.000 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.531 ms
Execution Time: 0.482 ms
```

#### Q05 (부분 인덱스)

[L05](#l05) · [Q05](#q05) — 전체 · 딥 페이지 · `price_asc`. SQL 은 [Q05](#q05) 의 PostgreSQL SQL 과 같다.

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| PostgreSQL 부분 인덱스 전 (8장) | 195.2 (193.7~198.3) |
| PostgreSQL 부분 인덱스 후 | 0.5 (0.5~0.6) |

```text
Sort  (cost=765.19..765.38 rows=75 width=59) (actual time=0.513..0.515 rows=50 loops=1)
  Sort Key: pm2_0.price, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=761.35..762.85 rows=75 width=59) (actual time=0.486..0.497 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=761.35..761.54 rows=75 width=59) (actual time=0.484..0.486 rows=80 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 36kB
              ->  Nested Loop Left Join  (cost=152.08..759.02 rows=75 width=59) (actual time=0.309..0.473 rows=80 loops=1)
                    ->  Nested Loop  (cost=151.64..590.08 rows=50 width=51) (actual time=0.300..0.411 rows=50 loops=1)
                          ->  Nested Loop  (cost=151.37..575.46 rows=50 width=38) (actual time=0.296..0.373 rows=50 loops=1)
                                ->  Limit  (cost=150.94..152.46 rows=50 width=16) (actual time=0.289..0.295 rows=50 loops=1)
                                      ->  Index Only Scan using ix_product_price_active on product pm1_0  (cost=0.43..144406.33 rows=4749460 width=16) (actual time=0.011..0.218 rows=5000 loops=1)
                                            Heap Fetches: 0
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=38) (actual time=0.001..0.001 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.001..0.001 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.001..0.001 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.548 ms
Execution Time: 0.561 ms
```

#### Q07 (부분 인덱스)

[L07](#l07) · [Q07](#q07) — 1번 브랜드 · 첫 페이지 · `latest`. SQL 은 [Q07](#q07) 의 PostgreSQL SQL 과 같다.

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| PostgreSQL 부분 인덱스 전 (8장) | 30.3 (27.9~32.5) |
| PostgreSQL 부분 인덱스 후 | 0.2 (0.2~0.2) |

```text
Sort  (cost=615.03..615.22 rows=75 width=67) (actual time=0.161..0.162 rows=50 loops=1)
  Sort Key: pm2_0.created_at DESC, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=611.20..612.70 rows=75 width=67) (actual time=0.137..0.149 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=611.20..611.38 rows=75 width=67) (actual time=0.135..0.137 rows=81 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 36kB
              ->  Nested Loop Left Join  (cost=1.57..608.86 rows=75 width=67) (actual time=0.021..0.123 rows=81 loops=1)
                    ->  Nested Loop  (cost=1.14..439.92 rows=50 width=59) (actual time=0.018..0.083 rows=50 loops=1)
                          ->  Nested Loop  (cost=0.86..425.30 rows=50 width=46) (actual time=0.016..0.062 rows=50 loops=1)
                                ->  Limit  (cost=0.43..2.30 rows=50 width=16) (actual time=0.010..0.014 rows=50 loops=1)
                                      ->  Index Only Scan Backward using ix_product_brand_latest_active on product pm1_0  (cost=0.43..17253.11 rows=462439 width=16) (actual time=0.009..0.012 rows=50 loops=1)
                                            Index Cond: (brand_id = 1)
                                            Heap Fetches: 0
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=46) (actual time=0.001..0.001 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.000..0.000 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.001..0.001 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.510 ms
Execution Time: 0.203 ms
```

#### Q08 (부분 인덱스)

[L08](#l08) · [Q08](#q08) — 1번 브랜드 · 첫 페이지 · `price_asc`. SQL 은 [Q08](#q08) 의 PostgreSQL SQL 과 같다.

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| PostgreSQL 부분 인덱스 전 (8장) | 22.5 (21.4~23.8) |
| PostgreSQL 부분 인덱스 후 | 0.3 (0.3~0.3) |

```text
Sort  (cost=615.03..615.22 rows=75 width=59) (actual time=0.271..0.272 rows=50 loops=1)
  Sort Key: pm2_0.price, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=611.20..612.70 rows=75 width=59) (actual time=0.244..0.256 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=611.20..611.38 rows=75 width=59) (actual time=0.241..0.243 rows=95 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 38kB
              ->  Nested Loop Left Join  (cost=1.57..608.86 rows=75 width=59) (actual time=0.031..0.229 rows=95 loops=1)
                    ->  Nested Loop  (cost=1.14..439.92 rows=50 width=51) (actual time=0.027..0.155 rows=50 loops=1)
                          ->  Nested Loop  (cost=0.86..425.30 rows=50 width=38) (actual time=0.022..0.131 rows=50 loops=1)
                                ->  Limit  (cost=0.43..2.30 rows=50 width=16) (actual time=0.013..0.017 rows=50 loops=1)
                                      ->  Index Only Scan using ix_product_brand_price_active on product pm1_0  (cost=0.43..17261.11 rows=462439 width=16) (actual time=0.012..0.014 rows=50 loops=1)
                                            Index Cond: (brand_id = 1)
                                            Heap Fetches: 0
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=38) (actual time=0.002..0.002 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.000..0.000 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.001..0.001 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.470 ms
Execution Time: 0.312 ms
```

#### Q10 (부분 인덱스)

[L10](#l10) · [Q10](#q10) — 1번 브랜드 · 딥 페이지 · `latest`. SQL 은 [Q10](#q10) 의 PostgreSQL SQL 과 같다.

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| PostgreSQL 부분 인덱스 전 (8장) | 41.2 (38.9~44.8) |
| PostgreSQL 부분 인덱스 후 | 0.5 (0.4~0.5) |

```text
Sort  (cost=799.71..799.89 rows=75 width=67) (actual time=0.456..0.458 rows=50 loops=1)
  Sort Key: pm2_0.created_at DESC, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=795.87..797.37 rows=75 width=67) (actual time=0.434..0.445 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=795.87..796.06 rows=75 width=67) (actual time=0.431..0.433 rows=64 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 34kB
              ->  Nested Loop Left Join  (cost=186.25..793.53 rows=75 width=67) (actual time=0.341..0.423 rows=64 loops=1)
                    ->  Nested Loop  (cost=185.81..624.60 rows=50 width=59) (actual time=0.338..0.391 rows=50 loops=1)
                          ->  Nested Loop  (cost=185.54..609.97 rows=50 width=46) (actual time=0.335..0.371 rows=50 loops=1)
                                ->  Limit  (cost=185.11..186.97 rows=50 width=16) (actual time=0.327..0.331 rows=50 loops=1)
                                      ->  Index Only Scan Backward using ix_product_brand_latest_active on product pm1_0  (cost=0.43..17253.11 rows=462439 width=16) (actual time=0.008..0.249 rows=5000 loops=1)
                                            Index Cond: (brand_id = 1)
                                            Heap Fetches: 0
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=46) (actual time=0.001..0.001 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.000..0.000 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.000..0.001 rows=1 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.525 ms
Execution Time: 0.502 ms
```

#### Q11 (부분 인덱스)

[L11](#l11) · [Q11](#q11) — 1번 브랜드 · 딥 페이지 · `price_asc`. SQL 은 [Q11](#q11) 의 PostgreSQL SQL 과 같다.

| 구분 | 실행 시간 (평균, ms) |
|---|---|
| PostgreSQL 부분 인덱스 전 (8장) | 25.6 (23.0~30.4) |
| PostgreSQL 부분 인덱스 후 | 0.6 (0.6~0.6) |

```text
Sort  (cost=799.79..799.98 rows=75 width=59) (actual time=0.558..0.560 rows=50 loops=1)
  Sort Key: pm2_0.price, pm2_0.id DESC
  Sort Method: quicksort  Memory: 32kB
  ->  GroupAggregate  (cost=795.96..797.46 rows=75 width=59) (actual time=0.524..0.540 rows=50 loops=1)
        Group Key: pm2_0.id, bm1_0.id
        ->  Sort  (cost=795.96..796.14 rows=75 width=59) (actual time=0.521..0.525 rows=155 loops=1)
              Sort Key: pm2_0.id DESC, bm1_0.id
              Sort Method: quicksort  Memory: 46kB
              ->  Nested Loop Left Join  (cost=186.33..793.62 rows=75 width=59) (actual time=0.336..0.505 rows=155 loops=1)
                    ->  Nested Loop  (cost=185.90..624.68 rows=50 width=51) (actual time=0.332..0.429 rows=50 loops=1)
                          ->  Nested Loop  (cost=185.63..610.06 rows=50 width=38) (actual time=0.329..0.407 rows=50 loops=1)
                                ->  Limit  (cost=185.19..187.06 rows=50 width=16) (actual time=0.325..0.329 rows=50 loops=1)
                                      ->  Index Only Scan using ix_product_brand_price_active on product pm1_0  (cost=0.43..17261.11 rows=462439 width=16) (actual time=0.011..0.250 rows=5000 loops=1)
                                            Index Cond: (brand_id = 1)
                                            Heap Fetches: 0
                                ->  Index Scan using product_pkey on product pm2_0  (cost=0.43..8.45 rows=1 width=38) (actual time=0.001..0.001 rows=1 loops=50)
                                      Index Cond: (id = pm1_0.id)
                          ->  Index Scan using brand_pkey on brand bm1_0  (cost=0.28..0.29 rows=1 width=21) (actual time=0.000..0.000 rows=1 loops=50)
                                Index Cond: (id = pm2_0.brand_id)
                    ->  Index Scan using ix_product_like_product on product_like plm1_0  (cost=0.43..2.33 rows=105 width=16) (actual time=0.001..0.001 rows=2 loops=50)
                          Index Cond: (product_id = pm2_0.id)
Planning Time: 0.469 ms
Execution Time: 0.598 ms
```

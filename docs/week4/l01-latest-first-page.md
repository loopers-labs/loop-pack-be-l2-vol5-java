# L01 — 전체 · 첫 페이지 · `latest`

`/api/v1/products?sort=latest&page=0&size=50`

[product-list-performance.md](product-list-performance.md) 에서 L01 에 관한 SQL·실행 계획·실행 시간·인덱스만 모았다. 측정 환경과 방법은 그 문서의 1·2장, 분석은 7·8-5·9-4 장에 있다.

- 실행 시간은 `EXPLAIN ANALYZE` 맨 위 노드의 `actual time` 끝 값(ms)이다. 워밍업 1회 + 측정 5회의 평균이고, 괄호는 최소~최대다.
- 실행 계획은 5번째 측정 결과다.
- MySQL "쿼리 변경 후" 는 데이터를 `seed.sql` 로 다시 만든 뒤 쟀다. 그래서 같은 데이터에서 변경 전 쿼리를 다시 잰 값을 따로 적었다.

## 0. 데이터

**테이블 구성** (측정 당시. 모든 테이블에 `id` PK, `created_at`, `updated_at`, `deleted_at` 이 있다)

| 테이블 | 주요 컬럼 | 관계 |
|---|---|---|
| `brand` | `name` | |
| `user` | `is_admin` | |
| `product` | `brand_id`, `name`, `price`, `stock` | `brand_id` → `brand.id` |
| `product_like` | `user_id`, `product_id` | `product_id` → `product.id`, `user_id` → `user.id`. `(user_id, product_id)` 유니크 |

L01 은 `product` 를 `brand` 와 조인하고 `product_like` 를 left join 해 상품별 좋아요 수를 센다. 좋아요 수는 저장해 두지 않고 매번 집계한다.

**건수**

| 데이터 | 브랜드 | 유저 | 상품 (삭제 / 삭제 안 됨) | 좋아요 |
|---|---|---|---|---|
| MySQL (`seed.sql`) | 1,000 | 1,000,000 | 5,000,000 (249,470 / 4,750,530) | 7,604,867 |
| MySQL 다시 만든 뒤 (`seed.sql`) | 1,000 | 1,000,000 | 5,000,000 (250,544 / 4,749,456) | 7,636,480 |
| PostgreSQL (`seed-pg.sql`) | 1,000 | 1,000,000 | 5,000,000 (250,257 / 4,749,743) | 7,514,034 |

규모·분포는 같지만 무작위 값이라 세 데이터는 행 단위로 같지 않다. L01 은 브랜드 조건이 없어 삭제되지 않은 상품 약 475만 건 전체가 대상이다.

## 1. 실행 시간 (평균, ms)

| 단계 | DB | 쿼리 | 인덱스 | 실행 시간 |
|---|---|---|---|---|
| 인덱스 적용 전 | MySQL | 변경 전 | PK, `ux_product_like_user_product` | 41,410.4 (40,286.0~43,414.0) |
| 인덱스 적용 후 | MySQL | 변경 전 | + `ix_product_*` 4개, `ix_product_like_product` | 65,350.6 (62,870.0~70,511.0) |
| 인덱스 적용 후 (데이터 다시 만든 뒤) | MySQL | 변경 전 | 위와 같음 | 44,669.0 (41,906.0~49,258.0) |
| 쿼리 변경 후 (데이터 다시 만든 뒤) | MySQL | CTE | 위와 같음 | 0.7 (0.7~0.8) |
| 인덱스 적용 | PostgreSQL | 변경 전 | MySQL "인덱스 적용 후" 와 같음 | 7,855.1 (7,237.1~8,464.0) |
| 쿼리 변경 후 | PostgreSQL | CTE | 위와 같음 | 227.2 (225.4~229.2) |
| 부분 인덱스 적용 후 | PostgreSQL | CTE | + 부분 인덱스 4개 | 0.2 (0.2~0.2) |

## 2. 인덱스

| 단계 | 인덱스 |
|---|---|
| 인덱스 적용 전 | PK, `product_like` 의 `ux_product_like_user_product (user_id, product_id)` |
| 인덱스 적용 후 (MySQL·PostgreSQL) | 위에 더해 `product`: `ix_product_latest (deleted_at, created_at, id)`, `ix_product_price (deleted_at, price, id)`, `ix_product_brand_latest (brand_id, deleted_at, created_at, id)`, `ix_product_brand_price (brand_id, deleted_at, price, id)` / `product_like`: `ix_product_like_product (product_id)` |
| PostgreSQL 부분 인덱스 | 위에 더해 아래 4개. DB 에 직접 만들었고 엔티티에는 없다. L01 이 쓰는 것은 `ix_product_latest_active` |

```sql
CREATE INDEX ix_product_latest_active ON product (created_at, id) WHERE deleted_at IS NULL;
CREATE INDEX ix_product_price_active ON product (price, id DESC) WHERE deleted_at IS NULL;
CREATE INDEX ix_product_brand_latest_active ON product (brand_id, created_at, id) WHERE deleted_at IS NULL;
CREATE INDEX ix_product_brand_price_active ON product (brand_id, price, id DESC) WHERE deleted_at IS NULL;
```

단계별 실행 계획에서 실제로 쓴 인덱스:

| 단계 | 쓴 인덱스 |
|---|---|
| MySQL 인덱스 적용 전 | 없음 (`product` Table scan). `product_like` 는 `ux_product_like_user_product` Covering index scan |
| MySQL 인덱스 적용 후 | `ix_product_brand_latest` (브랜드 1,000개마다 lookup), `ix_product_like_product` |
| MySQL 쿼리 변경 후 | CTE 안 `ix_product_latest` (reverse, covering), 바깥 PK·`ix_product_like_product` |
| PostgreSQL 인덱스 적용 | 없음 (`product`·`product_like`·`brand` 모두 Seq Scan) |
| PostgreSQL 쿼리 변경 후 | CTE 안 없음 (Parallel Seq Scan), 바깥 `product_pkey`·`brand_pkey`·`ix_product_like_product` |
| PostgreSQL 부분 인덱스 적용 후 | CTE 안 `ix_product_latest_active` (Index Only Scan Backward), 바깥 `product_pkey`·`brand_pkey`·`ix_product_like_product` |

## 3. SQL

### 3-1. 변경 전

MySQL

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

PostgreSQL (페이징만 다르다)


### 3-2. 변경 후 (CTE)

MySQL

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

PostgreSQL (CTE 안 페이징만 다르다)


## 4. 실행 계획

### 4-1. MySQL 인덱스 적용 전 — 41,410.4ms

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

언급 사항:
조인 순서
aggregate using temporary table - group by 로 인해 실행. 오래걸림. 이 경우에 디스크 기반 실행이 되었을 것.

### 4-2. MySQL 인덱스 적용 후 — 65,350.6ms

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

언급사항 : 인덱스 적용 되어 빨라질 줄 알았으나 오히려 느려짐.
group by 가 먼저 실행되어서 풀 테이블 서치가 일어나는건 동일. 그러면 왜 오히려 늦어졌을까?
가장 큰 이유는 첫번째 조인 에서 product + brand 접근	4,034ms	17,052ms	+13초증가했다.
이번에는 brand 가 driving 테이블이다. 
이전에는 outer 테이블 탐색시 brand 테이블에 대해 pk 기준 탐색을 했다.
이번에는 product 테이블에서 brand_id 는 세컨더리 인덱스다. 세컨더리 인덱스 이므로, pk 를 찾아서 다시 clustered 인덱스 를 찾으로 다시 탐색하는 비용때문에 오히려 첫번째 조인이 느려졌다. 

### 4-3. MySQL 쿼리 변경 후 — 0.7ms

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

언급할 내용: 쿼리 자체를 수정했다. 50개 먼저 찾고~ 조인. Materialize CTE page 내부 임시 테이블은 만드는거다.

### 4-4. PostgreSQL 인덱스 적용 — 7,855.1ms

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

언급 사항: hash join 두번, 조인 순서. 어떤 테이블이 probe

### 4-5. PostgreSQL 쿼리 변경 후 — 227.2ms

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

언급 사항: 이거도 5ms 내외의 결과값을 기대했는데 달랐다. 순서 자체는 product 테이블에서 50개를 먼저 찾고. 그걸 기준으로 조인 하는 방식은 맞다.
그런데 그 50개를 찾는게 인덱스 search 를 통해 한번에 가는게 아니다.
결국 원인은 del is NULL. del 이후의 인덱스는 적용이 안되는 문제. 그래서 자세히 보면 먼저 Filter: (deleted_at IS NULL) 를 통해 필터링을 먼저한다. 그 이후에   pm1_0.created_at 기준으로 sort 를 하고 limit 50을 하는 형태다. 

### 4-6. PostgreSQL 부분 인덱스 적용 후 — 0.2ms

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

언급 사항: partial index 를 적용 한후. 이러면 바로 인덱스를 통해 50개 바로 index only scan 으로 찾아낼 수 있다. 위와 다르다.

# 쿼리 성능 측정 원본 기록

> `scripts/perf/run.sh bench`가 측정할 때마다 아래에 섹션을 추가한다. 해석과 요약은 [query-performance.md](query-performance.md)에 정리한다.

## 측정: reference-100k (2026-10-14 01:41)

- 환경: MySQL 8.0.46-0ubuntu0.24.04.4, DB `loopers_perf`
- 방식: 쿼리별 워밍업 1회 후 5회 실행, SHOW PROFILES의 서버 실행 시간(ms). 1회 제한 30000ms
- 데이터: brands 200 / products 100000 (미삭제 94946) / product_likes 974368
- 인덱스: brands.PRIMARY(id), product_likes.PRIMARY(id), product_likes.uk_product_likes_user_product(user_id,product_id), products.PRIMARY(id)

| 쿼리 | 설명 | 평균 | 중앙값 | 최소 | 최대 | type | key | rows(예상) | Extra |
| --- | --- | ---: | ---: | ---: | ---: | --- | --- | ---: | --- |
| Q1_latest_all | 전체 상품 최신순 1페이지 | 45.30 | 45.20 | 43.08 | 46.77 | ALL | NULL | 99806 | Using where; Using filesort |
| Q2_latest_brand | 큰 브랜드(1) 최신순 1페이지 | 35.06 | 32.16 | 28.86 | 47.87 | ALL | NULL | 99806 | Using where; Using filesort |
| Q3_price_all | 전체 상품 가격 낮은순 1페이지 | 43.40 | 44.10 | 38.95 | 46.75 | ALL | NULL | 99806 | Using where; Using filesort |
| Q4_price_brand | 큰 브랜드(1) 가격 낮은순 1페이지 | 32.59 | 32.17 | 31.37 | 35.31 | ALL | NULL | 99806 | Using where; Using filesort |
| Q5_latest_deep_page | 전체 최신순 깊은 페이지 (page=2500, size=20) | 72.88 | 72.66 | 70.46 | 75.86 | ALL | NULL | 99806 | Using where; Using filesort |
| Q6_likes_all | 전체 상품 좋아요순 1페이지 | TIMEOUT(>30000) | - | - | - | ALL | NULL | 99806 | Using where; Using filesort |
| Q7_likes_brand | 큰 브랜드(1) 좋아요순 1페이지 | TIMEOUT(>30000) | - | - | - | ALL | NULL | 99806 | Using where; Using filesort |
| Q8_likes_small_brand | 작은 브랜드(200) 좋아요순 1페이지 | 25176.80 | 25123.85 | 24724.44 | 25798.18 | ALL | NULL | 99806 | Using where; Using filesort |
| Q9_like_count_page | 목록 1페이지 상품 20개의 좋아요 수 일괄 집계 (countByProductIds) | 148.23 | 149.84 | 136.05 | 163.60 | index | uk_product_likes_user_product | 970937 | Using where; Using index; Using temporary |
| Q10_like_count_detail | 상품 상세의 좋아요 수 (인기 상품 1) | 100.56 | 99.58 | 98.56 | 105.02 | index | uk_product_likes_user_product | 970937 | Using where; Using index |

<details><summary>실행 계획 상세 (EXPLAIN / EXPLAIN ANALYZE)</summary>

```text
-- ==================== Q1_latest_all: 전체 상품 최신순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows  | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 99806 |    50.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=10101 rows=20) (actual time=68.4..68.4 rows=20 loops=1)
    -> Sort: p.created_at DESC, p.id DESC, limit input to 20 row(s) per chunk  (cost=10101 rows=99806) (actual time=68.4..68.4 rows=20 loops=1)
        -> Filter: (p.deleted = false)  (cost=10101 rows=99806) (actual time=0.562..48.8 rows=94946 loops=1)
            -> Table scan on p  (cost=10101 rows=99806) (actual time=0.559..39.8 rows=100000 loops=1)


-- ==================== Q2_latest_brand: 큰 브랜드(1) 최신순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows  | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 99806 |     5.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=10101 rows=20) (actual time=39.6..39.6 rows=20 loops=1)
    -> Sort: p.created_at DESC, p.id DESC, limit input to 20 row(s) per chunk  (cost=10101 rows=99806) (actual time=39.6..39.6 rows=20 loops=1)
        -> Filter: ((p.brand_id = 1) and (p.deleted = false))  (cost=10101 rows=99806) (actual time=0.0948..38.5 rows=6694 loops=1)
            -> Table scan on p  (cost=10101 rows=99806) (actual time=0.0923..31.7 rows=100000 loops=1)


-- ==================== Q3_price_all: 전체 상품 가격 낮은순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.price asc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows  | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 99806 |    50.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=10101 rows=20) (actual time=53.7..53.7 rows=20 loops=1)
    -> Sort: p.price, p.id DESC, limit input to 20 row(s) per chunk  (cost=10101 rows=99806) (actual time=53.7..53.7 rows=20 loops=1)
        -> Filter: (p.deleted = false)  (cost=10101 rows=99806) (actual time=0.165..41.2 rows=94946 loops=1)
            -> Table scan on p  (cost=10101 rows=99806) (actual time=0.141..33.2 rows=100000 loops=1)


-- ==================== Q4_price_brand: 큰 브랜드(1) 가격 낮은순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY p.price asc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows  | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 99806 |     5.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=10101 rows=20) (actual time=40.9..40.9 rows=20 loops=1)
    -> Sort: p.price, p.id DESC, limit input to 20 row(s) per chunk  (cost=10101 rows=99806) (actual time=40.9..40.9 rows=20 loops=1)
        -> Filter: ((p.brand_id = 1) and (p.deleted = false))  (cost=10101 rows=99806) (actual time=0.105..39.7 rows=6694 loops=1)
            -> Table scan on p  (cost=10101 rows=99806) (actual time=0.103..32.2 rows=100000 loops=1)


-- ==================== Q5_latest_deep_page: 전체 최신순 깊은 페이지 (page=2500, size=20)
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 50000;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows  | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 99806 |    50.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit/Offset: 20/50000 row(s)  (cost=10101 rows=20) (actual time=83.9..83.9 rows=20 loops=1)
    -> Sort: p.created_at DESC, p.id DESC, limit input to 50020 row(s) per chunk  (cost=10101 rows=99806) (actual time=77.1..81.8 rows=50020 loops=1)
        -> Filter: (p.deleted = false)  (cost=10101 rows=99806) (actual time=0.0951..37.5 rows=94946 loops=1)
            -> Table scan on p  (cost=10101 rows=99806) (actual time=0.0931..29.5 rows=100000 loops=1)


-- ==================== Q6_likes_all: 전체 상품 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY (SELECT count(*) FROM product_likes l WHERE l.product_id=p.id) desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
| id | select_type        | table | partitions | type  | possible_keys | key                           | key_len | ref  | rows   | filtered | Extra                       |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
|  1 | PRIMARY            | p     | NULL       | ALL   | NULL          | NULL                          | NULL    | NULL |  99806 |    50.00 | Using where; Using filesort |
|  2 | DEPENDENT SUBQUERY | l     | NULL       | index | NULL          | uk_product_likes_user_product | 16      | NULL | 970937 |    10.00 | Using where; Using index    |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
-- EXPLAIN ANALYZE: 생략 (30000ms 안에 끝나지 않음)

-- ==================== Q7_likes_brand: 큰 브랜드(1) 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY (SELECT count(*) FROM product_likes l WHERE l.product_id=p.id) desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
| id | select_type        | table | partitions | type  | possible_keys | key                           | key_len | ref  | rows   | filtered | Extra                       |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
|  1 | PRIMARY            | p     | NULL       | ALL   | NULL          | NULL                          | NULL    | NULL |  99806 |     5.00 | Using where; Using filesort |
|  2 | DEPENDENT SUBQUERY | l     | NULL       | index | NULL          | uk_product_likes_user_product | 16      | NULL | 970937 |    10.00 | Using where; Using index    |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
-- EXPLAIN ANALYZE: 생략 (30000ms 안에 끝나지 않음)

-- ==================== Q8_likes_small_brand: 작은 브랜드(200) 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=200 ORDER BY (SELECT count(*) FROM product_likes l WHERE l.product_id=p.id) desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
| id | select_type        | table | partitions | type  | possible_keys | key                           | key_len | ref  | rows   | filtered | Extra                       |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
|  1 | PRIMARY            | p     | NULL       | ALL   | NULL          | NULL                          | NULL    | NULL |  99806 |     5.00 | Using where; Using filesort |
|  2 | DEPENDENT SUBQUERY | l     | NULL       | index | NULL          | uk_product_likes_user_product | 16      | NULL | 970937 |    10.00 | Using where; Using index    |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=10101 rows=20) (actual time=41865..41865 rows=20 loops=1)
    -> Sort: (select #2) DESC, p.id DESC, limit input to 20 row(s) per chunk  (cost=10101 rows=99806) (actual time=41865..41865 rows=20 loops=1)
        -> Filter: ((p.brand_id = 200) and (p.deleted = false))  (cost=10101 rows=99806) (actual time=0.309..39.2 rows=232 loops=1)
            -> Table scan on p  (cost=10101 rows=99806) (actual time=0.1..32.7 rows=100000 loops=1)
-> Select #2 (subquery in projection; dependent)
    -> Aggregate: count(0)  (cost=20100 rows=1) (actual time=180..180 rows=1 loops=232)
        -> Filter: (l.product_id = p.id)  (cost=10391 rows=97094) (actual time=44..180 rows=8.18 loops=232)
            -> Covering index scan on l using uk_product_likes_user_product  (cost=10391 rows=970937) (actual time=0.499..127 rows=974368 loops=232)


-- ==================== Q9_like_count_page: 목록 1페이지 상품 20개의 좋아요 수 일괄 집계 (countByProductIds)
SELECT product_id, count(*) AS total FROM product_likes WHERE product_id IN (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20) GROUP BY product_id;

-- EXPLAIN
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+--------+----------+-------------------------------------------+
| id | select_type | table         | partitions | type  | possible_keys                 | key                           | key_len | ref  | rows   | filtered | Extra                                     |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+--------+----------+-------------------------------------------+
|  1 | SIMPLE      | product_likes | NULL       | index | uk_product_likes_user_product | uk_product_likes_user_product | 16      | NULL | 970937 |    50.00 | Using where; Using index; Using temporary |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+--------+----------+-------------------------------------------+
-- EXPLAIN ANALYZE
-> Table scan on <temporary>  (actual time=211..211 rows=20 loops=1)
    -> Aggregate using temporary table  (actual time=211..211 rows=20 loops=1)
        -> Filter: (product_likes.product_id in (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20))  (cost=97775 rows=485468) (actual time=0.636..192 rows=94400 loops=1)
            -> Covering index scan on product_likes using uk_product_likes_user_product  (cost=97775 rows=970937) (actual time=0.635..136 rows=974368 loops=1)


-- ==================== Q10_like_count_detail: 상품 상세의 좋아요 수 (인기 상품 1)
SELECT product_id, count(*) AS total FROM product_likes WHERE product_id IN (1) GROUP BY product_id;

-- EXPLAIN
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+--------+----------+--------------------------+
| id | select_type | table         | partitions | type  | possible_keys                 | key                           | key_len | ref  | rows   | filtered | Extra                    |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+--------+----------+--------------------------+
|  1 | SIMPLE      | product_likes | NULL       | index | uk_product_likes_user_product | uk_product_likes_user_product | 16      | NULL | 970937 |    10.00 | Using where; Using index |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+--------+----------+--------------------------+
-- EXPLAIN ANALYZE
-> Group aggregate: count(0)  (cost=107484 rows=985) (actual time=168..168 rows=1 loops=1)
    -> Filter: (product_likes.product_id = 1)  (cost=97775 rows=97094) (actual time=0.459..167 rows=34627 loops=1)
        -> Covering index scan on product_likes using uk_product_likes_user_product  (cost=97775 rows=970937) (actual time=0.458..118 rows=974368 loops=1)


```

</details>

## 측정: reference-10m (2026-10-14 01:23)

- 환경: MySQL 8.0.46-0ubuntu0.24.04.4, DB `loopers_perf`
- 방식: 쿼리별 워밍업 1회 후 5회 실행, SHOW PROFILES의 서버 실행 시간(ms). 1회 제한 30000ms
- 데이터: brands 200 / products 10000000 (미삭제 9500504) / product_likes 9969594
- 인덱스: brands.PRIMARY(id), product_likes.PRIMARY(id), product_likes.uk_product_likes_user_product(user_id,product_id), products.PRIMARY(id)

| 쿼리 | 설명 | 평균 | 중앙값 | 최소 | 최대 | type | key | rows(예상) | Extra |
| --- | --- | ---: | ---: | ---: | ---: | --- | --- | ---: | --- |
| Q1_latest_all | 전체 상품 최신순 1페이지 | 4510.28 | 4499.81 | 4445.54 | 4612.40 | ALL | NULL | 9960468 | Using where; Using filesort |
| Q2_latest_brand | 큰 브랜드(1) 최신순 1페이지 | 3466.50 | 3444.84 | 3437.42 | 3543.18 | ALL | NULL | 9960468 | Using where; Using filesort |
| Q3_price_all | 전체 상품 가격 낮은순 1페이지 | 4045.59 | 4017.54 | 3919.30 | 4186.31 | ALL | NULL | 9960468 | Using where; Using filesort |
| Q4_price_brand | 큰 브랜드(1) 가격 낮은순 1페이지 | 3424.80 | 3459.85 | 3297.99 | 3533.49 | ALL | NULL | 9960468 | Using where; Using filesort |
| Q5_latest_deep_page | 전체 최신순 깊은 페이지 (page=2500, size=20) | 7600.35 | 7402.65 | 7159.63 | 8286.84 | ALL | NULL | 9960468 | Using where; Using filesort |
| Q6_likes_all | 전체 상품 좋아요순 1페이지 | TIMEOUT(>30000) | - | - | - | ALL | NULL | 9960468 | Using where; Using filesort |
| Q7_likes_brand | 큰 브랜드(1) 좋아요순 1페이지 | TIMEOUT(>30000) | - | - | - | ALL | NULL | 9960468 | Using where; Using filesort |
| Q8_likes_small_brand | 작은 브랜드(200) 좋아요순 1페이지 | TIMEOUT(>30000) | - | - | - | ALL | NULL | 9960468 | Using where; Using filesort |
| Q9_like_count_page | 목록 1페이지 상품 20개의 좋아요 수 일괄 집계 (countByProductIds) | 1526.14 | 1521.20 | 1469.54 | 1587.04 | index | uk_product_likes_user_product | 9752871 | Using where; Using index; Using temporary |
| Q10_like_count_detail | 상품 상세의 좋아요 수 (인기 상품 1) | 1317.86 | 1307.66 | 1286.84 | 1384.89 | index | uk_product_likes_user_product | 9752871 | Using where; Using index |

<details><summary>실행 계획 상세 (EXPLAIN / EXPLAIN ANALYZE)</summary>

```text
-- ==================== Q1_latest_all: 전체 상품 최신순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows    | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 9960468 |    50.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=1.04e+6 rows=20) (actual time=5633..5633 rows=20 loops=1)
    -> Sort: p.created_at DESC, p.id DESC, limit input to 20 row(s) per chunk  (cost=1.04e+6 rows=9.96e+6) (actual time=5633..5633 rows=20 loops=1)
        -> Filter: (p.deleted = false)  (cost=1.04e+6 rows=9.96e+6) (actual time=0.131..4215 rows=9.5e+6 loops=1)
            -> Table scan on p  (cost=1.04e+6 rows=9.96e+6) (actual time=0.129..3363 rows=10e+6 loops=1)


-- ==================== Q2_latest_brand: 큰 브랜드(1) 최신순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows    | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 9960468 |     5.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=1.04e+6 rows=20) (actual time=4543..4543 rows=20 loops=1)
    -> Sort: p.created_at DESC, p.id DESC, limit input to 20 row(s) per chunk  (cost=1.04e+6 rows=9.96e+6) (actual time=4543..4543 rows=20 loops=1)
        -> Filter: ((p.brand_id = 1) and (p.deleted = false))  (cost=1.04e+6 rows=9.96e+6) (actual time=0.249..4415 rows=670748 loops=1)
            -> Table scan on p  (cost=1.04e+6 rows=9.96e+6) (actual time=0.244..3719 rows=10e+6 loops=1)


-- ==================== Q3_price_all: 전체 상품 가격 낮은순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.price asc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows    | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 9960468 |    50.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=1.04e+6 rows=20) (actual time=5237..5237 rows=20 loops=1)
    -> Sort: p.price, p.id DESC, limit input to 20 row(s) per chunk  (cost=1.04e+6 rows=9.96e+6) (actual time=5237..5237 rows=20 loops=1)
        -> Filter: (p.deleted = false)  (cost=1.04e+6 rows=9.96e+6) (actual time=0.1..4029 rows=9.5e+6 loops=1)
            -> Table scan on p  (cost=1.04e+6 rows=9.96e+6) (actual time=0.0986..3213 rows=10e+6 loops=1)


-- ==================== Q4_price_brand: 큰 브랜드(1) 가격 낮은순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY p.price asc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows    | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 9960468 |     5.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=1.04e+6 rows=20) (actual time=4068..4068 rows=20 loops=1)
    -> Sort: p.price, p.id DESC, limit input to 20 row(s) per chunk  (cost=1.04e+6 rows=9.96e+6) (actual time=4068..4068 rows=20 loops=1)
        -> Filter: ((p.brand_id = 1) and (p.deleted = false))  (cost=1.04e+6 rows=9.96e+6) (actual time=0.11..3966 rows=670748 loops=1)
            -> Table scan on p  (cost=1.04e+6 rows=9.96e+6) (actual time=0.108..3322 rows=10e+6 loops=1)


-- ==================== Q5_latest_deep_page: 전체 최신순 깊은 페이지 (page=2500, size=20)
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 50000;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows    | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 9960468 |    50.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit/Offset: 20/50000 row(s)  (cost=1.04e+6 rows=20) (actual time=8741..8741 rows=20 loops=1)
    -> Sort: p.created_at DESC, p.id DESC, limit input to 50020 row(s) per chunk  (cost=1.04e+6 rows=9.96e+6) (actual time=8735..8739 rows=50020 loops=1)
        -> Filter: (p.deleted = false)  (cost=1.04e+6 rows=9.96e+6) (actual time=0.181..4290 rows=9.5e+6 loops=1)
            -> Table scan on p  (cost=1.04e+6 rows=9.96e+6) (actual time=0.179..3441 rows=10e+6 loops=1)


-- ==================== Q6_likes_all: 전체 상품 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY (SELECT count(*) FROM product_likes l WHERE l.product_id=p.id) desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+---------+----------+-----------------------------+
| id | select_type        | table | partitions | type  | possible_keys | key                           | key_len | ref  | rows    | filtered | Extra                       |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+---------+----------+-----------------------------+
|  1 | PRIMARY            | p     | NULL       | ALL   | NULL          | NULL                          | NULL    | NULL | 9960468 |    50.00 | Using where; Using filesort |
|  2 | DEPENDENT SUBQUERY | l     | NULL       | index | NULL          | uk_product_likes_user_product | 16      | NULL | 9752871 |    10.00 | Using where; Using index    |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+---------+----------+-----------------------------+
-- EXPLAIN ANALYZE: 생략 (30000ms 안에 끝나지 않음)

-- ==================== Q7_likes_brand: 큰 브랜드(1) 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY (SELECT count(*) FROM product_likes l WHERE l.product_id=p.id) desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+---------+----------+-----------------------------+
| id | select_type        | table | partitions | type  | possible_keys | key                           | key_len | ref  | rows    | filtered | Extra                       |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+---------+----------+-----------------------------+
|  1 | PRIMARY            | p     | NULL       | ALL   | NULL          | NULL                          | NULL    | NULL | 9960468 |     5.00 | Using where; Using filesort |
|  2 | DEPENDENT SUBQUERY | l     | NULL       | index | NULL          | uk_product_likes_user_product | 16      | NULL | 9752871 |    10.00 | Using where; Using index    |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+---------+----------+-----------------------------+
-- EXPLAIN ANALYZE: 생략 (30000ms 안에 끝나지 않음)

-- ==================== Q8_likes_small_brand: 작은 브랜드(200) 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=200 ORDER BY (SELECT count(*) FROM product_likes l WHERE l.product_id=p.id) desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+---------+----------+-----------------------------+
| id | select_type        | table | partitions | type  | possible_keys | key                           | key_len | ref  | rows    | filtered | Extra                       |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+---------+----------+-----------------------------+
|  1 | PRIMARY            | p     | NULL       | ALL   | NULL          | NULL                          | NULL    | NULL | 9960468 |     5.00 | Using where; Using filesort |
|  2 | DEPENDENT SUBQUERY | l     | NULL       | index | NULL          | uk_product_likes_user_product | 16      | NULL | 9752871 |    10.00 | Using where; Using index    |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+---------+----------+-----------------------------+
-- EXPLAIN ANALYZE: 생략 (30000ms 안에 끝나지 않음)

-- ==================== Q9_like_count_page: 목록 1페이지 상품 20개의 좋아요 수 일괄 집계 (countByProductIds)
SELECT product_id, count(*) AS total FROM product_likes WHERE product_id IN (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20) GROUP BY product_id;

-- EXPLAIN
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+---------+----------+-------------------------------------------+
| id | select_type | table         | partitions | type  | possible_keys                 | key                           | key_len | ref  | rows    | filtered | Extra                                     |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+---------+----------+-------------------------------------------+
|  1 | SIMPLE      | product_likes | NULL       | index | uk_product_likes_user_product | uk_product_likes_user_product | 16      | NULL | 9752871 |    50.00 | Using where; Using index; Using temporary |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+---------+----------+-------------------------------------------+
-- EXPLAIN ANALYZE
-> Table scan on <temporary>  (actual time=2300..2300 rows=20 loops=1)
    -> Aggregate using temporary table  (actual time=2300..2300 rows=20 loops=1)
        -> Filter: (product_likes.product_id in (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20))  (cost=1e+6 rows=4.88e+6) (actual time=0.84..2219 rows=346225 loops=1)
            -> Covering index scan on product_likes using uk_product_likes_user_product  (cost=1e+6 rows=9.75e+6) (actual time=0.838..1656 rows=9.97e+6 loops=1)


-- ==================== Q10_like_count_detail: 상품 상세의 좋아요 수 (인기 상품 1)
SELECT product_id, count(*) AS total FROM product_likes WHERE product_id IN (1) GROUP BY product_id;

-- EXPLAIN
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+---------+----------+--------------------------+
| id | select_type | table         | partitions | type  | possible_keys                 | key                           | key_len | ref  | rows    | filtered | Extra                    |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+---------+----------+--------------------------+
|  1 | SIMPLE      | product_likes | NULL       | index | uk_product_likes_user_product | uk_product_likes_user_product | 16      | NULL | 9752871 |    10.00 | Using where; Using index |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+---------+----------+--------------------------+
-- EXPLAIN ANALYZE
-> Group aggregate: count(0)  (cost=1.1e+6 rows=3123) (actual time=2226..2226 rows=1 loops=1)
    -> Filter: (product_likes.product_id = 1)  (cost=1e+6 rows=975287) (actual time=0.608..2217 rows=151236 loops=1)
        -> Covering index scan on product_likes using uk_product_likes_user_product  (cost=1e+6 rows=9.75e+6) (actual time=0.607..1639 rows=9.97e+6 loops=1)


```

</details>

## 측정: baseline (2026-10-14 09:25)

- 환경: Linux 6.18.44-fc-v114 (x86_64) / CPU Intel(R) Xeon(R) Processor @ 2.10GHz, 2코어 / 메모리 8GB / MySQL 8.0.46-0ubuntu0.24.04.4 / Java: openjdk version "21.0.12.1" 2026-08-18
- 방식: 쿼리별 워밍업 1회 후 '실행 횟수'만큼 실행, SHOW PROFILES의 서버 실행 시간(ms).
  기본 제한 30000ms, 워밍업이 10000ms 이상이면 3회만 실행. 쿼리별 제한: Q6_likes_all=60000 Q7_likes_brand=60000
- 결과 스냅샷: `docs/week4/perf-results/baseline/sql-*.tsv`
- 데이터: users 50000 / brands 200 (삭제 5) / products 100000 (미삭제 92859) / product_likes 974368
- 인덱스: brands.PRIMARY(id), product_likes.PRIMARY(id), product_likes.uk_product_likes_user_product(user_id,product_id), products.PRIMARY(id), users.PRIMARY(id)

| 쿼리 | 설명 | 실행 횟수 | 평균 | 중앙값 | 최소 | 최대 | type | key | rows(예상) | Extra |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- | --- | ---: | --- |
| Q1_latest_all | 전체 상품 최신순 1페이지 | 10 | 28.20 | 29.39 | 22.93 | 30.21 | ALL | NULL | 99806 | Using where; Using filesort |
| Q2_latest_brand | 큰 브랜드(1) 최신순 1페이지 | 10 | 23.13 | 23.47 | 18.92 | 27.94 | ALL | NULL | 99806 | Using where; Using filesort |
| Q3_price_all | 전체 상품 가격 낮은순 1페이지 | 10 | 22.87 | 22.77 | 22.47 | 23.79 | ALL | NULL | 99806 | Using where; Using filesort |
| Q4_price_brand | 큰 브랜드(1) 가격 낮은순 1페이지 | 10 | 23.67 | 23.57 | 22.28 | 24.87 | ALL | NULL | 99806 | Using where; Using filesort |
| Q5_latest_deep_page | 전체 최신순 깊은 페이지 (page=2500, size=20) | 10 | 58.87 | 57.08 | 55.71 | 66.79 | ALL | NULL | 99806 | Using where; Using filesort |
| Q6_likes_all | 전체 상품 좋아요순 1페이지 | - | TIMEOUT(>60000) | - | - | - | ALL | NULL | 99806 | Using where; Using filesort |
| Q7_likes_brand | 큰 브랜드(1) 좋아요순 1페이지 | - | TIMEOUT(>60000) | - | - | - | ALL | NULL | 99873 | Using where; Using filesort |
| Q8_likes_small_brand | 작은 브랜드(200) 좋아요순 1페이지 | 3 | 19212.88 | 19200.95 | 18732.10 | 19705.60 | ALL | NULL | 99873 | Using where; Using filesort |
| Q9_like_count_page | 목록 1페이지 상품 20개의 좋아요 수 일괄 집계 (countByProductIds) | 10 | 102.79 | 97.38 | 89.21 | 124.65 | index | uk_product_likes_user_product | 976305 | Using where; Using index; Using temporary |
| Q10_like_count_detail | 상품 상세의 좋아요 수 (인기 상품 1) | 10 | 85.97 | 86.78 | 73.41 | 92.82 | index | uk_product_likes_user_product | 976305 | Using where; Using index |

<details><summary>실행 계획 상세 (EXPLAIN / EXPLAIN ANALYZE)</summary>

```text
-- ==================== Q1_latest_all: 전체 상품 최신순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows  | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 99806 |    50.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=10101 rows=20) (actual time=33.3..33.3 rows=20 loops=1)
    -> Sort: p.created_at DESC, p.id DESC, limit input to 20 row(s) per chunk  (cost=10101 rows=99806) (actual time=33.3..33.3 rows=20 loops=1)
        -> Filter: (p.deleted = false)  (cost=10101 rows=99806) (actual time=0.0942..24.4 rows=92859 loops=1)
            -> Table scan on p  (cost=10101 rows=99806) (actual time=0.0926..18.9 rows=100000 loops=1)


-- ==================== Q2_latest_brand: 큰 브랜드(1) 최신순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows  | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 99806 |     5.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=10101 rows=20) (actual time=31.8..31.8 rows=20 loops=1)
    -> Sort: p.created_at DESC, p.id DESC, limit input to 20 row(s) per chunk  (cost=10101 rows=99806) (actual time=31.8..31.8 rows=20 loops=1)
        -> Filter: ((p.brand_id = 1) and (p.deleted = false))  (cost=10101 rows=99806) (actual time=0.229..30.7 rows=6694 loops=1)
            -> Table scan on p  (cost=10101 rows=99806) (actual time=0.226..24.7 rows=100000 loops=1)


-- ==================== Q3_price_all: 전체 상품 가격 낮은순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.price asc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows  | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 99806 |    50.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=10101 rows=20) (actual time=42.2..42.2 rows=20 loops=1)
    -> Sort: p.price, p.id DESC, limit input to 20 row(s) per chunk  (cost=10101 rows=99806) (actual time=42.2..42.2 rows=20 loops=1)
        -> Filter: (p.deleted = false)  (cost=10101 rows=99806) (actual time=0.157..32.5 rows=92859 loops=1)
            -> Table scan on p  (cost=10101 rows=99806) (actual time=0.154..25.5 rows=100000 loops=1)


-- ==================== Q4_price_brand: 큰 브랜드(1) 가격 낮은순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY p.price asc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows  | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 99806 |     5.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=10101 rows=20) (actual time=22.8..22.8 rows=20 loops=1)
    -> Sort: p.price, p.id DESC, limit input to 20 row(s) per chunk  (cost=10101 rows=99806) (actual time=22.8..22.8 rows=20 loops=1)
        -> Filter: ((p.brand_id = 1) and (p.deleted = false))  (cost=10101 rows=99806) (actual time=0.129..22.2 rows=6694 loops=1)
            -> Table scan on p  (cost=10101 rows=99806) (actual time=0.126..18 rows=100000 loops=1)


-- ==================== Q5_latest_deep_page: 전체 최신순 깊은 페이지 (page=2500, size=20)
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 50000;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows  | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 99806 |    50.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit/Offset: 20/50000 row(s)  (cost=10101 rows=20) (actual time=70.2..70.2 rows=20 loops=1)
    -> Sort: p.created_at DESC, p.id DESC, limit input to 50020 row(s) per chunk  (cost=10101 rows=99806) (actual time=65.6..68.5 rows=50020 loops=1)
        -> Filter: (p.deleted = false)  (cost=10101 rows=99806) (actual time=0.107..32.7 rows=92859 loops=1)
            -> Table scan on p  (cost=10101 rows=99806) (actual time=0.105..23.4 rows=100000 loops=1)


-- ==================== Q6_likes_all: 전체 상품 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY (SELECT count(*) FROM product_likes l WHERE l.product_id=p.id) desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
| id | select_type        | table | partitions | type  | possible_keys | key                           | key_len | ref  | rows   | filtered | Extra                       |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
|  1 | PRIMARY            | p     | NULL       | ALL   | NULL          | NULL                          | NULL    | NULL |  99873 |    50.00 | Using where; Using filesort |
|  2 | DEPENDENT SUBQUERY | l     | NULL       | index | NULL          | uk_product_likes_user_product | 16      | NULL | 976305 |    10.00 | Using where; Using index    |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
-- EXPLAIN ANALYZE: 생략 (60000ms 안에 끝나지 않음)

-- ==================== Q7_likes_brand: 큰 브랜드(1) 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY (SELECT count(*) FROM product_likes l WHERE l.product_id=p.id) desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
| id | select_type        | table | partitions | type  | possible_keys | key                           | key_len | ref  | rows   | filtered | Extra                       |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
|  1 | PRIMARY            | p     | NULL       | ALL   | NULL          | NULL                          | NULL    | NULL |  99873 |     5.00 | Using where; Using filesort |
|  2 | DEPENDENT SUBQUERY | l     | NULL       | index | NULL          | uk_product_likes_user_product | 16      | NULL | 976305 |    10.00 | Using where; Using index    |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
-- EXPLAIN ANALYZE: 생략 (60000ms 안에 끝나지 않음)

-- ==================== Q8_likes_small_brand: 작은 브랜드(200) 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=200 ORDER BY (SELECT count(*) FROM product_likes l WHERE l.product_id=p.id) desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
| id | select_type        | table | partitions | type  | possible_keys | key                           | key_len | ref  | rows   | filtered | Extra                       |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
|  1 | PRIMARY            | p     | NULL       | ALL   | NULL          | NULL                          | NULL    | NULL |  99873 |     5.00 | Using where; Using filesort |
|  2 | DEPENDENT SUBQUERY | l     | NULL       | index | NULL          | uk_product_likes_user_product | 16      | NULL | 976305 |    10.00 | Using where; Using index    |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=10108 rows=20) (actual time=32946..32946 rows=20 loops=1)
    -> Sort: (select #2) DESC, p.id DESC, limit input to 20 row(s) per chunk  (cost=10108 rows=99873) (actual time=32946..32946 rows=20 loops=1)
        -> Filter: ((p.brand_id = 200) and (p.deleted = false))  (cost=10108 rows=99873) (actual time=0.303..31.4 rows=232 loops=1)
            -> Table scan on p  (cost=10108 rows=99873) (actual time=0.118..25.6 rows=100000 loops=1)
-> Select #2 (subquery in projection; dependent)
    -> Aggregate: count(0)  (cost=20207 rows=1) (actual time=142..142 rows=1 loops=232)
        -> Filter: (l.product_id = p.id)  (cost=10444 rows=97631) (actual time=34.5..142 rows=8.18 loops=232)
            -> Covering index scan on l using uk_product_likes_user_product  (cost=10444 rows=976305) (actual time=0.403..99.7 rows=974368 loops=232)


-- ==================== Q9_like_count_page: 목록 1페이지 상품 20개의 좋아요 수 일괄 집계 (countByProductIds)
SELECT product_id, count(*) AS total FROM product_likes WHERE product_id IN (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20) GROUP BY product_id;

-- EXPLAIN
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+--------+----------+-------------------------------------------+
| id | select_type | table         | partitions | type  | possible_keys                 | key                           | key_len | ref  | rows   | filtered | Extra                                     |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+--------+----------+-------------------------------------------+
|  1 | SIMPLE      | product_likes | NULL       | index | uk_product_likes_user_product | uk_product_likes_user_product | 16      | NULL | 976305 |    50.00 | Using where; Using index; Using temporary |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+--------+----------+-------------------------------------------+
-- EXPLAIN ANALYZE
-> Table scan on <temporary>  (actual time=188..188 rows=20 loops=1)
    -> Aggregate using temporary table  (actual time=188..188 rows=20 loops=1)
        -> Filter: (product_likes.product_id in (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20))  (cost=98312 rows=488152) (actual time=0.627..171 rows=94400 loops=1)
            -> Covering index scan on product_likes using uk_product_likes_user_product  (cost=98312 rows=976305) (actual time=0.624..122 rows=974368 loops=1)


-- ==================== Q10_like_count_detail: 상품 상세의 좋아요 수 (인기 상품 1)
SELECT product_id, count(*) AS total FROM product_likes WHERE product_id IN (1) GROUP BY product_id;

-- EXPLAIN
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+--------+----------+--------------------------+
| id | select_type | table         | partitions | type  | possible_keys                 | key                           | key_len | ref  | rows   | filtered | Extra                    |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+--------+----------+--------------------------+
|  1 | SIMPLE      | product_likes | NULL       | index | uk_product_likes_user_product | uk_product_likes_user_product | 16      | NULL | 976305 |    10.00 | Using where; Using index |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+--------+----------+--------------------------+
-- EXPLAIN ANALYZE
-> Group aggregate: count(0)  (cost=108075 rows=988) (actual time=148..148 rows=1 loops=1)
    -> Filter: (product_likes.product_id = 1)  (cost=98312 rows=97631) (actual time=0.412..146 rows=34627 loops=1)
        -> Covering index scan on product_likes using uk_product_likes_user_product  (cost=98312 rows=976305) (actual time=0.411..103 rows=974368 loops=1)


```

</details>

## API 측정: baseline (2026-10-14 09:28)

- 환경: Linux 6.18.44-fc-v114 (x86_64) / CPU Intel(R) Xeon(R) Processor @ 2.10GHz, 2코어 / 메모리 8GB / MySQL 8.0.46-0ubuntu0.24.04.4 / Java: openjdk version "21.0.12.1" 2026-08-18
- 앱: commerce-api local 프로필 + 명령행 인자 `--spring.profiles.active=local --spring.jpa.hibernate.ddl-auto=none --spring.jpa.show-sql=false --datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3306/loopers_perf --datasource.mysql-jpa.main.username=root --datasource.mysql-jpa.main.password=*** --server.port=8080 `
  개선 후(after) 측정도 같은 계정(root)·옵션으로 실행해야 비교할 수 있다.
- 데이터 보존 확인: loopers_perf.products 시작 전 100000 / 종료 후 100000
- 방식: curl 순차 1개, X-USER-ID: 1. 워밍업 후 측정 요청의 time_total 백분위(ms, nearest-rank).
  첫 요청이 5000ms를 넘으면 워밍업 1회 + 측정 3회로 줄인다. 표의 '표본'이 요청별 실제 측정 수다.
- 요청당 SQL: 측정 직전 events_statements_summary_by_digest를 비우고, 측정 후 SCHEMA_NAME=loopers_perf(앱 커넥션) 실행 수를 요청 수로 나눈 값
- 응답 스냅샷: `docs/week4/perf-results/baseline/api-*.tsv` (마지막 응답의 id·price·likeCount)
- 데이터: users 50000 / brands 200 (삭제 5) / products 100000 (미삭제 92859) / product_likes 974368
- 인덱스: brands.PRIMARY(id), product_likes.PRIMARY(id), product_likes.uk_product_likes_user_product(user_id,product_id), products.PRIMARY(id), users.PRIMARY(id)

| 요청 | 설명 | 경로 | 워밍업 | 표본 | p50 | p95 | p99 | 최대 | 200 응답 | 요청당 SQL | 요청당 SELECT | 요청당 SQL 시간(ms) |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| R1 | 브랜드 1 가격순 1페이지 (과제 측정 대상) | `/api/v1/products?brandId=1&sort=price_asc&page=0&size=20` | 20 | 200 | 153.6 | 170.9 | 183.6 | 1053.4 | 200/200 | 14.0 | 4.0 | 135.2 |
| R2 | 브랜드 200 가격순 1페이지 | `/api/v1/products?brandId=200&sort=price_asc&page=0&size=20` | 20 | 200 | 147.5 | 167.2 | 175.7 | 194.5 | 200/200 | 14.0 | 4.0 | 134.5 |
| R3 | 브랜드 200 좋아요순 1페이지 | `/api/v1/products?brandId=200&sort=likes_desc&page=0&size=20` | 1 | 3 | 19459.1 | 19736.5 | 19736.5 | 19736.5 | 3/3 | 14.0 | 4.0 | 19411.2 |
| R4 | 상품 1 상세 | `/api/v1/products/1` | 20 | 200 | 96.5 | 109.1 | 121.5 | 132.2 | 200/200 | 14.0 | 4.0 | 85.9 |

<details><summary>요청별 SQL digest</summary>

```text
-- R1 /api/v1/products?brandId=1&sort=price_asc&page=0&size=20 (요청 200회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
801	64.78	SET autocommit = ?
400	38.81	COMMIT
400	35.59	SET SESSION TRANSACTION READ ONLY
400	34.72	SET SESSION TRANSACTION READ WRITE
200	22060.22	SELECT product_id , COUNT ( * ) AS total FROM product_likes WHERE product_id IN (...) GROUP BY product_id
200	4694.42	SELECT p . * FROM products p WHERE p . deleted = FALSE AND p . brand_id = ? ORDER BY p . price ASC , p . id DESC LIMIT ? OFFSET ?
200	62.94	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?
200	53.52	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
1	0.21	SELECT @@SESSION . auto_increment_increment AS auto_increment_increment , @@character_set_client AS character_set_client , @@character_set_connection AS charact
1	0.11	SET SESSION innodb_lock_wait_timeout = ?
1	0.06	SET character_set_results = ?
1	0.05	SELECT @@SESSION . transaction_read_only

-- R2 /api/v1/products?brandId=200&sort=price_asc&page=0&size=20 (요청 200회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
800	70.90	SET autocommit = ?
400	35.48	COMMIT
400	34.78	SET SESSION TRANSACTION READ ONLY
400	21.23	SET SESSION TRANSACTION READ WRITE
200	22120.13	SELECT product_id , COUNT ( * ) AS total FROM product_likes WHERE product_id IN (...) GROUP BY product_id
200	4513.70	SELECT p . * FROM products p WHERE p . deleted = FALSE AND p . brand_id = ? ORDER BY p . price ASC , p . id DESC LIMIT ? OFFSET ?
200	60.15	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?
200	52.70	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)

-- R3 /api/v1/products?brandId=200&sort=likes_desc&page=0&size=20 (요청 3회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
12	1.35	SET autocommit = ?
6	0.60	COMMIT
6	0.45	SET SESSION TRANSACTION READ ONLY
6	0.28	SET SESSION TRANSACTION READ WRITE
3	57910.96	SELECT p . * FROM products p WHERE p . deleted = FALSE AND p . brand_id = ? ORDER BY ( SELECT COUNT ( * ) FROM product_likes l WHERE l . product_id = p . id ) D
3	318.06	SELECT product_id , COUNT ( * ) AS total FROM product_likes WHERE product_id IN (...) GROUP BY product_id
3	0.96	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
3	0.86	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?

-- R4 /api/v1/products/1 (요청 200회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
800	56.74	SET autocommit = ?
400	33.36	SET SESSION TRANSACTION READ ONLY
400	33.00	COMMIT
400	27.56	SET SESSION TRANSACTION READ WRITE
200	16887.87	SELECT product_id , COUNT ( * ) AS total FROM product_likes WHERE product_id IN (...) GROUP BY product_id
200	53.60	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?
200	40.68	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
200	37.22	SELECT pje1_0 . id , pje1_0 . brand_id , pje1_0 . created_at , pje1_0 . deleted , pje1_0 . name , pje1_0 . price , pje1_0 . stock FROM products pje1_0 WHERE pje

```

</details>

## 측정: baseline-10m (2026-10-14 10:47)

- 환경: Linux 6.18.44-fc-v114 (x86_64) / CPU Intel(R) Xeon(R) Processor @ 2.10GHz, 2코어 / 메모리 8GB / MySQL 8.0.46-0ubuntu0.24.04.4 / Java: openjdk version "21.0.12.1" 2026-08-18
- 디스크 여유: 26G (MySQL 데이터 디렉터리) / buffer pool 128MB
- 방식: 쿼리별 워밍업 1회 후 '실행 횟수'만큼 실행, SHOW PROFILES의 서버 실행 시간(ms).
  기본 제한 30000ms, 워밍업이 10000ms 이상이면 3회만 실행. 쿼리별 제한: Q6_likes_all=60000 Q7_likes_brand=60000 Q8_likes_small_brand=60000
- 결과 스냅샷: `docs/week4/perf-results/baseline-10m/sql-*.tsv`
- 데이터: users 500000 / brands 200 (삭제 5) / products 10000000 (미삭제 9293588) / product_likes 9969594
- 테이블 크기(데이터+인덱스): brands 0MB / product_likes 720MB / products 724MB / users 15MB
- 인덱스: brands.PRIMARY(id), product_likes.PRIMARY(id), product_likes.uk_product_likes_user_product(user_id,product_id), products.PRIMARY(id), users.PRIMARY(id)

| 쿼리 | 설명 | 실행 횟수 | 평균 | 중앙값 | 최소 | 최대 | type | key | rows(예상) | Extra |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- | --- | ---: | --- |
| Q1_latest_all | 전체 상품 최신순 1페이지 | 10 | 3303.79 | 3278.65 | 3200.11 | 3442.94 | ALL | NULL | 9713133 | Using where; Using filesort |
| Q2_latest_brand | 큰 브랜드(1) 최신순 1페이지 | 10 | 2591.45 | 2583.88 | 2478.68 | 2739.59 | ALL | NULL | 9713133 | Using where; Using filesort |
| Q3_price_all | 전체 상품 가격 낮은순 1페이지 | 10 | 3166.09 | 3191.22 | 2812.25 | 3383.25 | ALL | NULL | 9713133 | Using where; Using filesort |
| Q4_price_brand | 큰 브랜드(1) 가격 낮은순 1페이지 | 10 | 2545.83 | 2550.26 | 2227.25 | 2759.17 | ALL | NULL | 9713133 | Using where; Using filesort |
| Q5_latest_deep_page | 전체 최신순 깊은 페이지 (page=2500, size=20) | 10 | 6456.30 | 6450.19 | 5944.94 | 7039.27 | ALL | NULL | 9713133 | Using where; Using filesort |
| Q6_likes_all | 전체 상품 좋아요순 1페이지 | - | TIMEOUT(>60000) | - | - | - | ALL | NULL | 9713133 | Using where; Using filesort |
| Q7_likes_brand | 큰 브랜드(1) 좋아요순 1페이지 | - | TIMEOUT(>60000) | - | - | - | ALL | NULL | 9713133 | Using where; Using filesort |
| Q8_likes_small_brand | 작은 브랜드(200) 좋아요순 1페이지 | - | TIMEOUT(>60000) | - | - | - | ALL | NULL | 9713133 | Using where; Using filesort |
| Q9_like_count_page | 목록 1페이지 상품 20개의 좋아요 수 일괄 집계 (countByProductIds) | 10 | 1247.13 | 1257.94 | 1092.67 | 1309.98 | index | uk_product_likes_user_product | 9725089 | Using where; Using index; Using temporary |
| Q10_like_count_detail | 상품 상세의 좋아요 수 (인기 상품 1) | 10 | 1016.64 | 1013.65 | 885.19 | 1100.48 | index | uk_product_likes_user_product | 9725089 | Using where; Using index |

<details><summary>실행 계획 상세 (EXPLAIN / EXPLAIN ANALYZE)</summary>

```text
-- ==================== Q1_latest_all: 전체 상품 최신순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows    | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 9713133 |    50.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=1.01e+6 rows=20) (actual time=4320..4320 rows=20 loops=1)
    -> Sort: p.created_at DESC, p.id DESC, limit input to 20 row(s) per chunk  (cost=1.01e+6 rows=9.71e+6) (actual time=4320..4320 rows=20 loops=1)
        -> Filter: (p.deleted = false)  (cost=1.01e+6 rows=9.71e+6) (actual time=0.197..3279 rows=9.29e+6 loops=1)
            -> Table scan on p  (cost=1.01e+6 rows=9.71e+6) (actual time=0.195..2596 rows=10e+6 loops=1)


-- ==================== Q2_latest_brand: 큰 브랜드(1) 최신순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows    | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 9713133 |     5.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=1.01e+6 rows=20) (actual time=3258..3258 rows=20 loops=1)
    -> Sort: p.created_at DESC, p.id DESC, limit input to 20 row(s) per chunk  (cost=1.01e+6 rows=9.71e+6) (actual time=3258..3258 rows=20 loops=1)
        -> Filter: ((p.brand_id = 1) and (p.deleted = false))  (cost=1.01e+6 rows=9.71e+6) (actual time=0.252..3173 rows=670748 loops=1)
            -> Table scan on p  (cost=1.01e+6 rows=9.71e+6) (actual time=0.248..2633 rows=10e+6 loops=1)


-- ==================== Q3_price_all: 전체 상품 가격 낮은순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.price asc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows    | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 9713133 |    50.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=1.01e+6 rows=20) (actual time=4288..4288 rows=20 loops=1)
    -> Sort: p.price, p.id DESC, limit input to 20 row(s) per chunk  (cost=1.01e+6 rows=9.71e+6) (actual time=4288..4288 rows=20 loops=1)
        -> Filter: (p.deleted = false)  (cost=1.01e+6 rows=9.71e+6) (actual time=0.129..3347 rows=9.29e+6 loops=1)
            -> Table scan on p  (cost=1.01e+6 rows=9.71e+6) (actual time=0.127..2659 rows=10e+6 loops=1)


-- ==================== Q4_price_brand: 큰 브랜드(1) 가격 낮은순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY p.price asc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows    | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 9713133 |     5.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=1.01e+6 rows=20) (actual time=3132..3132 rows=20 loops=1)
    -> Sort: p.price, p.id DESC, limit input to 20 row(s) per chunk  (cost=1.01e+6 rows=9.71e+6) (actual time=3132..3132 rows=20 loops=1)
        -> Filter: ((p.brand_id = 1) and (p.deleted = false))  (cost=1.01e+6 rows=9.71e+6) (actual time=0.151..3056 rows=670748 loops=1)
            -> Table scan on p  (cost=1.01e+6 rows=9.71e+6) (actual time=0.147..2545 rows=10e+6 loops=1)


-- ==================== Q5_latest_deep_page: 전체 최신순 깊은 페이지 (page=2500, size=20)
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 50000;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows    | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 9713133 |    50.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit/Offset: 20/50000 row(s)  (cost=1.01e+6 rows=20) (actual time=7382..7382 rows=20 loops=1)
    -> Sort: p.created_at DESC, p.id DESC, limit input to 50020 row(s) per chunk  (cost=1.01e+6 rows=9.71e+6) (actual time=7378..7381 rows=50020 loops=1)
        -> Filter: (p.deleted = false)  (cost=1.01e+6 rows=9.71e+6) (actual time=0.137..3421 rows=9.29e+6 loops=1)
            -> Table scan on p  (cost=1.01e+6 rows=9.71e+6) (actual time=0.134..2714 rows=10e+6 loops=1)


-- ==================== Q6_likes_all: 전체 상품 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY (SELECT count(*) FROM product_likes l WHERE l.product_id=p.id) desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+---------+----------+-----------------------------+
| id | select_type        | table | partitions | type  | possible_keys | key                           | key_len | ref  | rows    | filtered | Extra                       |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+---------+----------+-----------------------------+
|  1 | PRIMARY            | p     | NULL       | ALL   | NULL          | NULL                          | NULL    | NULL | 9713133 |    50.00 | Using where; Using filesort |
|  2 | DEPENDENT SUBQUERY | l     | NULL       | index | NULL          | uk_product_likes_user_product | 16      | NULL | 9725089 |    10.00 | Using where; Using index    |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+---------+----------+-----------------------------+
-- EXPLAIN ANALYZE: 생략 (60000ms 안에 끝나지 않음)

-- ==================== Q7_likes_brand: 큰 브랜드(1) 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY (SELECT count(*) FROM product_likes l WHERE l.product_id=p.id) desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+---------+----------+-----------------------------+
| id | select_type        | table | partitions | type  | possible_keys | key                           | key_len | ref  | rows    | filtered | Extra                       |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+---------+----------+-----------------------------+
|  1 | PRIMARY            | p     | NULL       | ALL   | NULL          | NULL                          | NULL    | NULL | 9713133 |     5.00 | Using where; Using filesort |
|  2 | DEPENDENT SUBQUERY | l     | NULL       | index | NULL          | uk_product_likes_user_product | 16      | NULL | 9725089 |    10.00 | Using where; Using index    |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+---------+----------+-----------------------------+
-- EXPLAIN ANALYZE: 생략 (60000ms 안에 끝나지 않음)

-- ==================== Q8_likes_small_brand: 작은 브랜드(200) 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=200 ORDER BY (SELECT count(*) FROM product_likes l WHERE l.product_id=p.id) desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+---------+----------+-----------------------------+
| id | select_type        | table | partitions | type  | possible_keys | key                           | key_len | ref  | rows    | filtered | Extra                       |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+---------+----------+-----------------------------+
|  1 | PRIMARY            | p     | NULL       | ALL   | NULL          | NULL                          | NULL    | NULL | 9713133 |     5.00 | Using where; Using filesort |
|  2 | DEPENDENT SUBQUERY | l     | NULL       | index | NULL          | uk_product_likes_user_product | 16      | NULL | 9725089 |    10.00 | Using where; Using index    |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+---------+----------+-----------------------------+
-- EXPLAIN ANALYZE: 생략 (60000ms 안에 끝나지 않음)

-- ==================== Q9_like_count_page: 목록 1페이지 상품 20개의 좋아요 수 일괄 집계 (countByProductIds)
SELECT product_id, count(*) AS total FROM product_likes WHERE product_id IN (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20) GROUP BY product_id;

-- EXPLAIN
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+---------+----------+-------------------------------------------+
| id | select_type | table         | partitions | type  | possible_keys                 | key                           | key_len | ref  | rows    | filtered | Extra                                     |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+---------+----------+-------------------------------------------+
|  1 | SIMPLE      | product_likes | NULL       | index | uk_product_likes_user_product | uk_product_likes_user_product | 16      | NULL | 9725089 |    50.00 | Using where; Using index; Using temporary |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+---------+----------+-------------------------------------------+
-- EXPLAIN ANALYZE
-> Table scan on <temporary>  (actual time=2001..2001 rows=20 loops=1)
    -> Aggregate using temporary table  (actual time=2001..2001 rows=20 loops=1)
        -> Filter: (product_likes.product_id in (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20))  (cost=998644 rows=4.86e+6) (actual time=0.627..1928 rows=346225 loops=1)
            -> Covering index scan on product_likes using uk_product_likes_user_product  (cost=998644 rows=9.73e+6) (actual time=0.625..1442 rows=9.97e+6 loops=1)


-- ==================== Q10_like_count_detail: 상품 상세의 좋아요 수 (인기 상품 1)
SELECT product_id, count(*) AS total FROM product_likes WHERE product_id IN (1) GROUP BY product_id;

-- EXPLAIN
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+---------+----------+--------------------------+
| id | select_type | table         | partitions | type  | possible_keys                 | key                           | key_len | ref  | rows    | filtered | Extra                    |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+---------+----------+--------------------------+
|  1 | SIMPLE      | product_likes | NULL       | index | uk_product_likes_user_product | uk_product_likes_user_product | 16      | NULL | 9725089 |    10.00 | Using where; Using index |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+---------+----------+--------------------------+
-- EXPLAIN ANALYZE
-> Group aggregate: count(0)  (cost=1.1e+6 rows=3119) (actual time=1651..1651 rows=1 loops=1)
    -> Filter: (product_likes.product_id = 1)  (cost=998644 rows=972509) (actual time=0.488..1644 rows=151236 loops=1)
        -> Covering index scan on product_likes using uk_product_likes_user_product  (cost=998644 rows=9.73e+6) (actual time=0.486..1215 rows=9.97e+6 loops=1)


```

</details>

## API 측정: baseline-10m (2026-10-14 10:53)

- 환경: Linux 6.18.44-fc-v114 (x86_64) / CPU Intel(R) Xeon(R) Processor @ 2.10GHz, 2코어 / 메모리 8GB / MySQL 8.0.46-0ubuntu0.24.04.4 / Java: openjdk version "21.0.12.1" 2026-08-18
- 디스크 여유: 26G (MySQL 데이터 디렉터리) / buffer pool 128MB
- 앱: commerce-api local 프로필 + 명령행 인자 `--spring.profiles.active=local --spring.jpa.hibernate.ddl-auto=none --spring.jpa.show-sql=false --datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3306/loopers_perf --datasource.mysql-jpa.main.username=root --datasource.mysql-jpa.main.password=*** --server.port=8080 `
  개선 후(after) 측정도 같은 계정(root)·옵션으로 실행해야 비교할 수 있다.
- 데이터 보존 확인: loopers_perf.products 시작 전 10000000 / 종료 후 10000000
- 방식: curl 순차 1개, X-USER-ID: 1. 워밍업 후 측정 요청의 time_total 백분위(ms, nearest-rank).
  첫 요청이 99999999ms를 넘으면 워밍업 1회 + 측정 3회로 줄인다. 표의 '표본'이 요청별 실제 측정 수다.
- 요청당 SQL: 측정 직전 events_statements_summary_by_digest를 비우고, 측정 후 SCHEMA_NAME=loopers_perf(앱 커넥션) 실행 수를 요청 수로 나눈 값
- 응답 스냅샷: `docs/week4/perf-results/baseline-10m/api-*.tsv` (마지막 응답의 id·price·likeCount)
- 데이터: users 500000 / brands 200 (삭제 5) / products 10000000 (미삭제 9293588) / product_likes 9969594
- 테이블 크기(데이터+인덱스): brands 0MB / product_likes 720MB / products 724MB / users 15MB
- 인덱스: brands.PRIMARY(id), product_likes.PRIMARY(id), product_likes.uk_product_likes_user_product(user_id,product_id), products.PRIMARY(id), users.PRIMARY(id)

| 요청 | 설명 | 경로 | 워밍업 | 표본 | p50 | p95 | p99 | 최대 | 200 응답 | 요청당 SQL | 요청당 SELECT | 요청당 SQL 시간(ms) |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| R1 | 브랜드 1 가격순 1페이지 (과제 측정 대상) | `/api/v1/products?brandId=1&sort=price_asc&page=0&size=20` | 3 | 30 | 3771.0 | 3941.0 | 3993.9 | 3993.9 | 30/30 | 14.2 | 4.1 | 3745.3 |
| R2 | 브랜드 200 가격순 1페이지 | `/api/v1/products?brandId=200&sort=price_asc&page=0&size=20` | 3 | 30 | 3716.6 | 3980.1 | 4032.9 | 4032.9 | 30/30 | 14.0 | 4.0 | 3720.0 |
| R3 | 브랜드 200 좋아요순 1페이지 | `/api/v1/products?brandId=200&sort=likes_desc&page=0&size=20` | 1 | 0 | TIMEOUT(>60s) | - | - | - | - | - | - | - |
| R4 | 상품 1 상세 | `/api/v1/products/1` | 3 | 30 | 1036.2 | 1104.8 | 1135.8 | 1135.8 | 30/30 | 14.0 | 4.0 | 1000.2 |

<details><summary>요청별 SQL digest</summary>

```text
-- R1 /api/v1/products?brandId=1&sort=price_asc&page=0&size=20 (요청 30회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
121	9.55	SET autocommit = ?
60	6.79	COMMIT
60	6.14	SET SESSION TRANSACTION READ ONLY
60	5.67	SET SESSION TRANSACTION READ WRITE
30	76568.57	SELECT p . * FROM products p WHERE p . deleted = FALSE AND p . brand_id = ? ORDER BY p . price ASC , p . id DESC LIMIT ? OFFSET ?
30	35730.08	SELECT product_id , COUNT ( * ) AS total FROM product_likes WHERE product_id IN (...) GROUP BY product_id
30	17.47	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
30	12.96	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?
1	0.27	SELECT @@SESSION . auto_increment_increment AS auto_increment_increment , @@character_set_client AS character_set_client , @@character_set_connection AS charact
1	0.10	SET SESSION innodb_lock_wait_timeout = ?
1	0.05	SELECT @@SESSION . transaction_read_only
1	0.04	SET character_set_results = ?

-- R2 /api/v1/products?brandId=200&sort=price_asc&page=0&size=20 (요청 30회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
120	9.07	SET autocommit = ?
60	6.66	COMMIT
60	5.77	SET SESSION TRANSACTION READ ONLY
60	4.36	SET SESSION TRANSACTION READ WRITE
30	73967.65	SELECT p . * FROM products p WHERE p . deleted = FALSE AND p . brand_id = ? ORDER BY p . price ASC , p . id DESC LIMIT ? OFFSET ?
30	37584.84	SELECT product_id , COUNT ( * ) AS total FROM product_likes WHERE product_id IN (...) GROUP BY product_id
30	11.60	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
30	9.94	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?

-- R3 /api/v1/products?brandId=200&sort=likes_desc&page=0&size=20: 60초 제한 초과로 측정하지 않음 (SQL 집계 없음)

-- R4 /api/v1/products/1 (요청 30회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
120	19.77	SET autocommit = ?
60	7.22	SET SESSION TRANSACTION READ ONLY
60	5.82	COMMIT
60	5.65	SET SESSION TRANSACTION READ WRITE
30	29938.17	SELECT product_id , COUNT ( * ) AS total FROM product_likes WHERE product_id IN (...) GROUP BY product_id
30	10.29	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?
30	10.04	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
30	9.00	SELECT pje1_0 . id , pje1_0 . brand_id , pje1_0 . created_at , pje1_0 . deleted , pje1_0 . name , pje1_0 . price , pje1_0 . stock FROM products pje1_0 WHERE pje

```

</details>


## 측정: after-index (2026-10-15 22:11)

- 환경: Linux 6.18.44-fc-v114 (x86_64) / CPU Intel(R) Xeon(R) Processor @ 2.10GHz, 2코어 / 메모리 8GB / MySQL 8.0.46-0ubuntu0.24.04.4 / Java: openjdk version "21.0.12.1" 2026-08-18
- 디스크 여유: 27G (MySQL 데이터 디렉터리) / buffer pool 128MB
- 방식: 쿼리별 워밍업 1회 후 '실행 횟수'만큼 실행, SHOW PROFILES의 서버 실행 시간(ms).
  기본 제한 30000ms, 워밍업이 10000ms 이상이면 3회만 실행. 쿼리별 제한: Q6_likes_all=60000 Q7_likes_brand=60000
- 결과 스냅샷: `docs/week4/perf-results/after-index/sql-*.tsv`
- 데이터: users 50000 / brands 200 (삭제 5) / products 100000 (미삭제 92859) / product_likes 974368
- 테이블 크기(데이터+인덱스): brands 0MB(인덱스 0MB) / product_likes 95MB(인덱스 52MB) / products 27MB(인덱스 18MB) / users 2MB(인덱스 0MB)
- 인덱스: brands.PRIMARY(id), product_likes.idx_product_likes_product(product_id), product_likes.PRIMARY(id), product_likes.uk_product_likes_user_product(user_id,product_id), products.idx_products_brand_created(brand_id,created_at), products.idx_products_brand_likes(brand_id,like_count,id), products.idx_products_brand_price(brand_id,price,id), products.idx_products_created(created_at,id), products.idx_products_likes(like_count,id), products.idx_products_price(price,id), products.PRIMARY(id), users.PRIMARY(id)

| 쿼리 | 설명 | 실행 횟수 | 평균 | 중앙값 | 최소 | 최대 | type | key | rows(예상) | Extra |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- | --- | ---: | --- |
| Q1_latest_all | 전체 상품 최신순 1페이지 | 10 | 0.19 | 0.13 | 0.11 | 0.57 | index | idx_products_created | 20 | Using where |
| Q2_latest_brand | 큰 브랜드(1) 최신순 1페이지 | 10 | 0.64 | 0.58 | 0.39 | 1.15 | index | idx_products_created | 1158 | Using where |
| Q3_price_all | 전체 상품 가격 낮은순 1페이지 | 10 | 0.26 | 0.23 | 0.19 | 0.47 | index | idx_products_price | 20 | Using where |
| Q4_price_brand | 큰 브랜드(1) 가격 낮은순 1페이지 | 10 | 0.72 | 0.67 | 0.49 | 1.22 | ref | idx_products_brand_price | 13076 | Using where |
| Q5_latest_deep_page | 전체 최신순 깊은 페이지 (page=2500, size=20) | 10 | 77.96 | 64.88 | 52.10 | 192.99 | ALL | NULL | 99694 | Using where; Using filesort |
| Q6_likes_all | 전체 상품 좋아요순 1페이지 | 10 | 0.22 | 0.19 | 0.12 | 0.41 | index | idx_products_likes | 20 | Using where |
| Q7_likes_brand | 큰 브랜드(1) 좋아요순 1페이지 | 10 | 0.65 | 0.57 | 0.46 | 1.45 | ref | idx_products_brand_likes | 13076 | Using where |
| Q8_likes_small_brand | 작은 브랜드(200) 좋아요순 1페이지 | 10 | 0.23 | 0.22 | 0.17 | 0.43 | ref | idx_products_brand_likes | 245 | Using where |
| Q9_like_count_page | 목록 1페이지 상품 20개의 좋아요 수 일괄 조회 (findLikeCounts) | 10 | 0.19 | 0.17 | 0.11 | 0.40 | range | PRIMARY | 20 | Using where |
| Q10_like_count_detail | 상품 상세의 좋아요 수 (인기 상품 1) | 10 | 0.09 | 0.07 | 0.06 | 0.22 | const | PRIMARY | 1 | NULL |

<details><summary>실행 계획 상세 (EXPLAIN / EXPLAIN ANALYZE)</summary>

```text
-- ==================== Q1_latest_all: 전체 상품 최신순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+-------+---------------+----------------------+---------+------+------+----------+-------------+
| id | select_type | table | partitions | type  | possible_keys | key                  | key_len | ref  | rows | filtered | Extra       |
+----+-------------+-------+------------+-------+---------------+----------------------+---------+------+------+----------+-------------+
|  1 | SIMPLE      | p     | NULL       | index | NULL          | idx_products_created | 16      | NULL |   20 |    50.00 | Using where |
+----+-------------+-------+------------+-------+---------------+----------------------+---------+------+------+----------+-------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=1.03 rows=10) (actual time=0.0672..0.0992 rows=20 loops=1)
    -> Filter: (p.deleted = false)  (cost=1.03 rows=10) (actual time=0.0665..0.0976 rows=20 loops=1)
        -> Index scan on p using idx_products_created  (cost=1.03 rows=20) (actual time=0.065..0.0943 rows=21 loops=1)


-- ==================== Q2_latest_brand: 큰 브랜드(1) 최신순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+-------+------------------------------------------------------------------------------+----------------------+---------+------+------+----------+-------------+
| id | select_type | table | partitions | type  | possible_keys                                                                | key                  | key_len | ref  | rows | filtered | Extra       |
+----+-------------+-------+------------+-------+------------------------------------------------------------------------------+----------------------+---------+------+------+----------+-------------+
|  1 | SIMPLE      | p     | NULL       | index | idx_products_brand_price,idx_products_brand_created,idx_products_brand_likes | idx_products_created | 16      | NULL | 1158 |     6.56 | Using where |
+----+-------------+-------+------------+-------+------------------------------------------------------------------------------+----------------------+---------+------+------+----------+-------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=4.84 rows=20) (actual time=0.213..0.372 rows=20 loops=1)
    -> Filter: ((p.brand_id = 1) and (p.deleted = false))  (cost=4.84 rows=75.9) (actual time=0.212..0.371 rows=20 loops=1)
        -> Index scan on p using idx_products_created  (cost=4.84 rows=1158) (actual time=0.206..0.358 rows=218 loops=1)


-- ==================== Q3_price_all: 전체 상품 가격 낮은순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.price asc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+-------+---------------+--------------------+---------+------+------+----------+-------------+
| id | select_type | table | partitions | type  | possible_keys | key                | key_len | ref  | rows | filtered | Extra       |
+----+-------------+-------+------------+-------+---------------+--------------------+---------+------+------+----------+-------------+
|  1 | SIMPLE      | p     | NULL       | index | NULL          | idx_products_price | 16      | NULL |   20 |    50.00 | Using where |
+----+-------------+-------+------------+-------+---------------+--------------------+---------+------+------+----------+-------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=1.03 rows=10) (actual time=0.069..0.0962 rows=20 loops=1)
    -> Filter: (p.deleted = false)  (cost=1.03 rows=10) (actual time=0.0679..0.094 rows=20 loops=1)
        -> Index scan on p using idx_products_price  (cost=1.03 rows=20) (actual time=0.0664..0.0911 rows=20 loops=1)


-- ==================== Q4_price_brand: 큰 브랜드(1) 가격 낮은순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY p.price asc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+------------------------------------------------------------------------------+--------------------------+---------+-------+-------+----------+-------------+
| id | select_type | table | partitions | type | possible_keys                                                                | key                      | key_len | ref   | rows  | filtered | Extra       |
+----+-------------+-------+------------+------+------------------------------------------------------------------------------+--------------------------+---------+-------+-------+----------+-------------+
|  1 | SIMPLE      | p     | NULL       | ref  | idx_products_brand_price,idx_products_brand_created,idx_products_brand_likes | idx_products_brand_price | 8       | const | 13076 |    50.00 | Using where |
+----+-------------+-------+------------+------+------------------------------------------------------------------------------+--------------------------+---------+-------+-------+----------+-------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=1063 rows=20) (actual time=0.513..0.517 rows=20 loops=1)
    -> Filter: (p.deleted = false)  (cost=1063 rows=6538) (actual time=0.512..0.515 rows=20 loops=1)
        -> Index lookup on p using idx_products_brand_price (brand_id=1)  (cost=1063 rows=13076) (actual time=0.506..0.51 rows=33 loops=1)


-- ==================== Q5_latest_deep_page: 전체 최신순 깊은 페이지 (page=2500, size=20)
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 50000;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows  | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 99694 |    50.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit/Offset: 20/50000 row(s)  (cost=10106 rows=20) (actual time=61.8..61.8 rows=20 loops=1)
    -> Sort: p.created_at DESC, p.id DESC, limit input to 50020 row(s) per chunk  (cost=10106 rows=99694) (actual time=57.7..60.4 rows=50020 loops=1)
        -> Filter: (p.deleted = false)  (cost=10106 rows=99694) (actual time=0.092..27.9 rows=92859 loops=1)
            -> Table scan on p  (cost=10106 rows=99694) (actual time=0.09..21.9 rows=100000 loops=1)


-- ==================== Q6_likes_all: 전체 상품 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.like_count desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+-------+---------------+--------------------+---------+------+------+----------+-------------+
| id | select_type | table | partitions | type  | possible_keys | key                | key_len | ref  | rows | filtered | Extra       |
+----+-------------+-------+------------+-------+---------------+--------------------+---------+------+------+----------+-------------+
|  1 | SIMPLE      | p     | NULL       | index | NULL          | idx_products_likes | 16      | NULL |   20 |    50.00 | Using where |
+----+-------------+-------+------------+-------+---------------+--------------------+---------+------+------+----------+-------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=1.03 rows=10) (actual time=0.0708..0.0991 rows=20 loops=1)
    -> Filter: (p.deleted = false)  (cost=1.03 rows=10) (actual time=0.0702..0.0976 rows=20 loops=1)
        -> Index scan on p using idx_products_likes  (cost=1.03 rows=20) (actual time=0.068..0.0936 rows=25 loops=1)


-- ==================== Q7_likes_brand: 큰 브랜드(1) 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY p.like_count desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+------------------------------------------------------------------------------+--------------------------+---------+-------+-------+----------+-------------+
| id | select_type | table | partitions | type | possible_keys                                                                | key                      | key_len | ref   | rows  | filtered | Extra       |
+----+-------------+-------+------------+------+------------------------------------------------------------------------------+--------------------------+---------+-------+-------+----------+-------------+
|  1 | SIMPLE      | p     | NULL       | ref  | idx_products_brand_price,idx_products_brand_created,idx_products_brand_likes | idx_products_brand_likes | 8       | const | 13076 |    50.00 | Using where |
+----+-------------+-------+------------+------+------------------------------------------------------------------------------+--------------------------+---------+-------+-------+----------+-------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=1063 rows=20) (actual time=0.487..0.493 rows=20 loops=1)
    -> Filter: (p.deleted = false)  (cost=1063 rows=6538) (actual time=0.486..0.491 rows=20 loops=1)
        -> Index lookup on p using idx_products_brand_likes (brand_id=1)  (cost=1063 rows=13076) (actual time=0.484..0.487 rows=21 loops=1)


-- ==================== Q8_likes_small_brand: 작은 브랜드(200) 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=200 ORDER BY p.like_count desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+------------------------------------------------------------------------------+--------------------------+---------+-------+------+----------+-------------+
| id | select_type | table | partitions | type | possible_keys                                                                | key                      | key_len | ref   | rows | filtered | Extra       |
+----+-------------+-------+------------+------+------------------------------------------------------------------------------+--------------------------+---------+-------+------+----------+-------------+
|  1 | SIMPLE      | p     | NULL       | ref  | idx_products_brand_price,idx_products_brand_created,idx_products_brand_likes | idx_products_brand_likes | 8       | const |  245 |    50.00 | Using where |
+----+-------------+-------+------------+------+------------------------------------------------------------------------------+--------------------------+---------+-------+------+----------+-------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=73.5 rows=20) (actual time=0.0708..0.0762 rows=20 loops=1)
    -> Filter: (p.deleted = false)  (cost=73.5 rows=122) (actual time=0.0703..0.0746 rows=20 loops=1)
        -> Index lookup on p using idx_products_brand_likes (brand_id=200)  (cost=73.5 rows=245) (actual time=0.0688..0.0716 rows=21 loops=1)


-- ==================== Q9_like_count_page: 목록 1페이지 상품 20개의 좋아요 수 일괄 조회 (findLikeCounts)
SELECT p.id AS product_id, p.like_count AS total FROM products p WHERE p.id IN (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20);

-- EXPLAIN
+----+-------------+-------+------------+-------+---------------+---------+---------+------+------+----------+-------------+
| id | select_type | table | partitions | type  | possible_keys | key     | key_len | ref  | rows | filtered | Extra       |
+----+-------------+-------+------------+-------+---------------+---------+---------+------+------+----------+-------------+
|  1 | SIMPLE      | p     | NULL       | range | PRIMARY       | PRIMARY | 8       | NULL |   20 |   100.00 | Using where |
+----+-------------+-------+------------+-------+---------------+---------+---------+------+------+----------+-------------+
-- EXPLAIN ANALYZE
-> Filter: (p.id in (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20))  (cost=9.02 rows=20) (actual time=0.0264..0.0415 rows=20 loops=1)
    -> Index range scan on p using PRIMARY over (id = 1) OR (id = 2) OR (18 more)  (cost=9.02 rows=20) (actual time=0.025..0.0381 rows=20 loops=1)


-- ==================== Q10_like_count_detail: 상품 상세의 좋아요 수 (인기 상품 1)
SELECT p.id AS product_id, p.like_count AS total FROM products p WHERE p.id IN (1);

-- EXPLAIN
+----+-------------+-------+------------+-------+---------------+---------+---------+-------+------+----------+-------+
| id | select_type | table | partitions | type  | possible_keys | key     | key_len | ref   | rows | filtered | Extra |
+----+-------------+-------+------------+-------+---------------+---------+---------+-------+------+----------+-------+
|  1 | SIMPLE      | p     | NULL       | const | PRIMARY       | PRIMARY | 8       | const |    1 |   100.00 | NULL  |
+----+-------------+-------+------------+-------+---------------+---------+---------+-------+------+----------+-------+
-- EXPLAIN ANALYZE
-> Rows fetched before execution  (cost=0..0 rows=1) (actual time=81e-6..149e-6 rows=1 loops=1)


```

</details>

## API 측정: after-index (2026-10-15 22:11)

- 환경: Linux 6.18.44-fc-v114 (x86_64) / CPU Intel(R) Xeon(R) Processor @ 2.10GHz, 2코어 / 메모리 8GB / MySQL 8.0.46-0ubuntu0.24.04.4 / Java: openjdk version "21.0.12.1" 2026-08-18
- 디스크 여유: 27G (MySQL 데이터 디렉터리) / buffer pool 128MB
- 앱: commerce-api local 프로필 + 명령행 인자 `--spring.profiles.active=local --spring.jpa.hibernate.ddl-auto=none --spring.jpa.show-sql=false --datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3306/loopers_perf --datasource.mysql-jpa.main.username=root --datasource.mysql-jpa.main.password=*** --server.port=8080 `
  개선 후(after) 측정도 같은 계정(root)·옵션으로 실행해야 비교할 수 있다.
- 데이터 보존 확인: loopers_perf.products 시작 전 100000 / 종료 후 100000
- 방식: curl 순차 1개, X-USER-ID: 1. 워밍업 후 측정 요청의 time_total 백분위(ms, nearest-rank).
  첫 요청이 5000ms를 넘으면 워밍업 1회 + 측정 3회로 줄인다. 표의 '표본'이 요청별 실제 측정 수다.
- 요청당 SQL: 측정 직전 events_statements_summary_by_digest를 비우고, 측정 후 SCHEMA_NAME=loopers_perf(앱 커넥션) 실행 수를 요청 수로 나눈 값
- 응답 스냅샷: `docs/week4/perf-results/after-index/api-*.tsv` (마지막 응답의 id·price·likeCount)
- 데이터: users 50000 / brands 200 (삭제 5) / products 100000 (미삭제 92859) / product_likes 974368
- 테이블 크기(데이터+인덱스): brands 0MB(인덱스 0MB) / product_likes 95MB(인덱스 52MB) / products 27MB(인덱스 18MB) / users 2MB(인덱스 0MB)
- 인덱스: brands.PRIMARY(id), product_likes.idx_product_likes_product(product_id), product_likes.PRIMARY(id), product_likes.uk_product_likes_user_product(user_id,product_id), products.idx_products_brand_created(brand_id,created_at), products.idx_products_brand_likes(brand_id,like_count,id), products.idx_products_brand_price(brand_id,price,id), products.idx_products_created(created_at,id), products.idx_products_likes(like_count,id), products.idx_products_price(price,id), products.PRIMARY(id), users.PRIMARY(id)

| 요청 | 설명 | 경로 | 워밍업 | 표본 | p50 | p95 | p99 | 최대 | 200 응답 | 요청당 SQL | 요청당 SELECT | 요청당 SQL 시간(ms) |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| R1 | 브랜드 1 가격순 1페이지 (과제 측정 대상) | `/api/v1/products?brandId=1&sort=price_asc&page=0&size=20` | 20 | 200 | 14.6 | 25.5 | 35.7 | 37.7 | 200/200 | 14.0 | 4.0 | 2.2 |
| R2 | 브랜드 200 가격순 1페이지 | `/api/v1/products?brandId=200&sort=price_asc&page=0&size=20` | 20 | 200 | 9.6 | 15.8 | 18.9 | 33.5 | 200/200 | 14.0 | 4.0 | 1.7 |
| R3 | 브랜드 200 좋아요순 1페이지 | `/api/v1/products?brandId=200&sort=likes_desc&page=0&size=20` | 20 | 200 | 7.8 | 14.1 | 17.3 | 25.1 | 200/200 | 14.0 | 4.0 | 1.6 |
| R4 | 상품 1 상세 | `/api/v1/products/1` | 20 | 200 | 6.8 | 11.6 | 23.4 | 29.9 | 200/200 | 14.0 | 4.0 | 1.3 |

<details><summary>요청별 SQL digest</summary>

```text
-- R1 /api/v1/products?brandId=1&sort=price_asc&page=0&size=20 (요청 200회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
800	41.32	SET autocommit = ?
400	26.73	SET SESSION TRANSACTION READ ONLY
400	25.64	COMMIT
400	14.51	SET SESSION TRANSACTION READ WRITE
200	168.38	SELECT p . * FROM products p WHERE p . deleted = FALSE AND p . brand_id = ? ORDER BY p . price ASC , p . id DESC LIMIT ? OFFSET ?
200	58.11	SELECT pje1_0 . id , pje1_0 . like_count FROM products pje1_0 WHERE pje1_0 . id IN (...)
200	56.50	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
200	42.18	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?

-- R2 /api/v1/products?brandId=200&sort=price_asc&page=0&size=20 (요청 200회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
800	47.12	SET autocommit = ?
400	23.45	COMMIT
400	23.31	SET SESSION TRANSACTION READ ONLY
400	11.63	SET SESSION TRANSACTION READ WRITE
200	78.65	SELECT p . * FROM products p WHERE p . deleted = FALSE AND p . brand_id = ? ORDER BY p . price ASC , p . id DESC LIMIT ? OFFSET ?
200	60.85	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
200	58.54	SELECT pje1_0 . id , pje1_0 . like_count FROM products pje1_0 WHERE pje1_0 . id IN (...)
200	35.65	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?

-- R3 /api/v1/products?brandId=200&sort=likes_desc&page=0&size=20 (요청 200회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
800	46.46	SET autocommit = ?
400	26.97	SET SESSION TRANSACTION READ ONLY
400	22.31	COMMIT
400	16.02	SET SESSION TRANSACTION READ WRITE
200	69.49	SELECT p . * FROM products p WHERE p . deleted = FALSE AND p . brand_id = ? ORDER BY p . like_count DESC , p . id DESC LIMIT ? OFFSET ?
200	53.01	SELECT pje1_0 . id , pje1_0 . like_count FROM products pje1_0 WHERE pje1_0 . id IN (...)
200	50.25	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
200	37.38	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?

-- R4 /api/v1/products/1 (요청 200회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
800	44.24	SET autocommit = ?
400	31.20	SET SESSION TRANSACTION READ ONLY
400	21.85	COMMIT
400	12.02	SET SESSION TRANSACTION READ WRITE
200	40.39	SELECT pje1_0 . id , pje1_0 . like_count FROM products pje1_0 WHERE pje1_0 . id IN (...)
200	39.19	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
200	37.45	SELECT pje1_0 . id , pje1_0 . brand_id , pje1_0 . created_at , pje1_0 . deleted , pje1_0 . like_count , pje1_0 . name , pje1_0 . price , pje1_0 . stock FROM pro
200	36.49	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?

```

</details>

## 측정: after-index-10m (2026-10-15 22:17)

- 환경: Linux 6.18.44-fc-v114 (x86_64) / CPU Intel(R) Xeon(R) Processor @ 2.10GHz, 2코어 / 메모리 8GB / MySQL 8.0.46-0ubuntu0.24.04.4 / Java: openjdk version "21.0.12.1" 2026-08-18
- 디스크 여유: 24G (MySQL 데이터 디렉터리) / buffer pool 128MB
- 방식: 쿼리별 워밍업 1회 후 '실행 횟수'만큼 실행, SHOW PROFILES의 서버 실행 시간(ms).
  기본 제한 30000ms, 워밍업이 10000ms 이상이면 3회만 실행. 쿼리별 제한: Q6_likes_all=60000 Q7_likes_brand=60000 Q8_likes_small_brand=60000
- 결과 스냅샷: `docs/week4/perf-results/after-index-10m/sql-*.tsv`
- 데이터: users 500000 / brands 200 (삭제 5) / products 10000000 (미삭제 9293588) / product_likes 9969594
- 테이블 크기(데이터+인덱스): brands 0MB(인덱스 0MB) / product_likes 957MB(인덱스 522MB) / products 2504MB(인덱스 1694MB) / users 15MB(인덱스 0MB)
- 인덱스: brands.PRIMARY(id), product_likes.idx_product_likes_product(product_id), product_likes.PRIMARY(id), product_likes.uk_product_likes_user_product(user_id,product_id), products.idx_products_brand_created(brand_id,created_at), products.idx_products_brand_likes(brand_id,like_count,id), products.idx_products_brand_price(brand_id,price,id), products.idx_products_created(created_at,id), products.idx_products_likes(like_count,id), products.idx_products_price(price,id), products.PRIMARY(id), users.PRIMARY(id)

| 쿼리 | 설명 | 실행 횟수 | 평균 | 중앙값 | 최소 | 최대 | type | key | rows(예상) | Extra |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- | --- | ---: | --- |
| Q1_latest_all | 전체 상품 최신순 1페이지 | 10 | 0.18 | 0.16 | 0.10 | 0.45 | index | idx_products_created | 20 | Using where |
| Q2_latest_brand | 큰 브랜드(1) 최신순 1페이지 | 10 | 1.17 | 1.04 | 0.95 | 2.15 | index | idx_products_created | 981 | Using where |
| Q3_price_all | 전체 상품 가격 낮은순 1페이지 | 10 | 0.26 | 0.24 | 0.20 | 0.42 | index | idx_products_price | 20 | Using where |
| Q4_price_brand | 큰 브랜드(1) 가격 낮은순 1페이지 | 10 | 1.12 | 1.07 | 0.89 | 1.52 | ref | idx_products_brand_price | 1420280 | Using where |
| Q5_latest_deep_page | 전체 최신순 깊은 페이지 (page=2500, size=20) | 10 | 445.35 | 447.89 | 405.84 | 485.45 | index | idx_products_created | 50020 | Using where |
| Q6_likes_all | 전체 상품 좋아요순 1페이지 | 10 | 0.17 | 0.14 | 0.12 | 0.40 | index | idx_products_likes | 20 | Using where |
| Q7_likes_brand | 큰 브랜드(1) 좋아요순 1페이지 | 10 | 0.77 | 0.60 | 0.50 | 1.79 | ref | idx_products_brand_likes | 1420280 | Using where |
| Q8_likes_small_brand | 작은 브랜드(200) 좋아요순 1페이지 | 10 | 1.39 | 1.23 | 0.90 | 2.36 | ref | idx_products_brand_likes | 50556 | Using where |
| Q9_like_count_page | 목록 1페이지 상품 20개의 좋아요 수 일괄 조회 (findLikeCounts) | 10 | 0.16 | 0.12 | 0.11 | 0.35 | range | PRIMARY | 20 | Using where |
| Q10_like_count_detail | 상품 상세의 좋아요 수 (인기 상품 1) | 10 | 0.13 | 0.10 | 0.07 | 0.30 | const | PRIMARY | 1 | NULL |

<details><summary>실행 계획 상세 (EXPLAIN / EXPLAIN ANALYZE)</summary>

```text
-- ==================== Q1_latest_all: 전체 상품 최신순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+-------+---------------+----------------------+---------+------+------+----------+-------------+
| id | select_type | table | partitions | type  | possible_keys | key                  | key_len | ref  | rows | filtered | Extra       |
+----+-------------+-------+------------+-------+---------------+----------------------+---------+------+------+----------+-------------+
|  1 | SIMPLE      | p     | NULL       | index | NULL          | idx_products_created | 16      | NULL |   20 |    50.00 | Using where |
+----+-------------+-------+------------+-------+---------------+----------------------+---------+------+------+----------+-------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=1.1 rows=10) (actual time=0.115..0.172 rows=20 loops=1)
    -> Filter: (p.deleted = false)  (cost=1.1 rows=10) (actual time=0.115..0.17 rows=20 loops=1)
        -> Index scan on p using idx_products_created  (cost=1.1 rows=20) (actual time=0.112..0.166 rows=22 loops=1)


-- ==================== Q2_latest_brand: 큰 브랜드(1) 최신순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+-------+------------------------------------------------------------------------------+----------------------+---------+------+------+----------+-------------+
| id | select_type | table | partitions | type  | possible_keys                                                                | key                  | key_len | ref  | rows | filtered | Extra       |
+----+-------------+-------+------------+-------+------------------------------------------------------------------------------+----------------------+---------+------+------+----------+-------------+
|  1 | SIMPLE      | p     | NULL       | index | idx_products_brand_price,idx_products_brand_created,idx_products_brand_likes | idx_products_created | 16      | NULL |  981 |     7.13 | Using where |
+----+-------------+-------+------------+-------+------------------------------------------------------------------------------+----------------------+---------+------+------+----------+-------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=14.7 rows=20) (actual time=0.534..1.44 rows=20 loops=1)
    -> Filter: ((p.brand_id = 1) and (p.deleted = false))  (cost=14.7 rows=70) (actual time=0.533..1.43 rows=20 loops=1)
        -> Index scan on p using idx_products_created  (cost=14.7 rows=981) (actual time=0.525..1.41 rows=248 loops=1)


-- ==================== Q3_price_all: 전체 상품 가격 낮은순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.price asc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+-------+---------------+--------------------+---------+------+------+----------+-------------+
| id | select_type | table | partitions | type  | possible_keys | key                | key_len | ref  | rows | filtered | Extra       |
+----+-------------+-------+------------+-------+---------------+--------------------+---------+------+------+----------+-------------+
|  1 | SIMPLE      | p     | NULL       | index | NULL          | idx_products_price | 16      | NULL |   20 |    50.00 | Using where |
+----+-------------+-------+------------+-------+---------------+--------------------+---------+------+------+----------+-------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=1.1 rows=10) (actual time=0.127..0.185 rows=20 loops=1)
    -> Filter: (p.deleted = false)  (cost=1.1 rows=10) (actual time=0.126..0.183 rows=20 loops=1)
        -> Index scan on p using idx_products_price  (cost=1.1 rows=20) (actual time=0.123..0.179 rows=22 loops=1)


-- ==================== Q4_price_brand: 큰 브랜드(1) 가격 낮은순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY p.price asc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+------------------------------------------------------------------------------+--------------------------+---------+-------+---------+----------+-------------+
| id | select_type | table | partitions | type | possible_keys                                                                | key                      | key_len | ref   | rows    | filtered | Extra       |
+----+-------------+-------+------------+------+------------------------------------------------------------------------------+--------------------------+---------+-------+---------+----------+-------------+
|  1 | SIMPLE      | p     | NULL       | ref  | idx_products_brand_price,idx_products_brand_created,idx_products_brand_likes | idx_products_brand_price | 8       | const | 1420280 |    50.00 | Using where |
+----+-------------+-------+------------+------+------------------------------------------------------------------------------+--------------------------+---------+-------+---------+----------+-------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=220521 rows=20) (actual time=1.89..1.9 rows=20 loops=1)
    -> Filter: (p.deleted = false)  (cost=220521 rows=710140) (actual time=1.89..1.89 rows=20 loops=1)
        -> Index lookup on p using idx_products_brand_price (brand_id=1)  (cost=220521 rows=1.42e+6) (actual time=1.88..1.89 rows=31 loops=1)


-- ==================== Q5_latest_deep_page: 전체 최신순 깊은 페이지 (page=2500, size=20)
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 50000;

-- EXPLAIN
+----+-------------+-------+------------+-------+---------------+----------------------+---------+------+-------+----------+-------------+
| id | select_type | table | partitions | type  | possible_keys | key                  | key_len | ref  | rows  | filtered | Extra       |
+----+-------------+-------+------------+-------+---------------+----------------------+---------+------+-------+----------+-------------+
|  1 | SIMPLE      | p     | NULL       | index | NULL          | idx_products_created | 16      | NULL | 50020 |    50.00 | Using where |
+----+-------------+-------+------------+-------+---------------+----------------------+---------+------+-------+----------+-------------+
-- EXPLAIN ANALYZE
-> Limit/Offset: 20/50000 row(s)  (cost=2761 rows=0) (actual time=481..485 rows=20 loops=1)
    -> Filter: (p.deleted = false)  (cost=2761 rows=25010) (actual time=5.79..483 rows=50020 loops=1)
        -> Index scan on p using idx_products_created  (cost=2761 rows=50020) (actual time=5.79..479 rows=53807 loops=1)


-- ==================== Q6_likes_all: 전체 상품 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.like_count desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+-------+---------------+--------------------+---------+------+------+----------+-------------+
| id | select_type | table | partitions | type  | possible_keys | key                | key_len | ref  | rows | filtered | Extra       |
+----+-------------+-------+------------+-------+---------------+--------------------+---------+------+------+----------+-------------+
|  1 | SIMPLE      | p     | NULL       | index | NULL          | idx_products_likes | 16      | NULL |   20 |    50.00 | Using where |
+----+-------------+-------+------------+-------+---------------+--------------------+---------+------+------+----------+-------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=1.1 rows=10) (actual time=0.0934..0.132 rows=20 loops=1)
    -> Filter: (p.deleted = false)  (cost=1.1 rows=10) (actual time=0.0926..0.13 rows=20 loops=1)
        -> Index scan on p using idx_products_likes  (cost=1.1 rows=20) (actual time=0.0906..0.126 rows=25 loops=1)


-- ==================== Q7_likes_brand: 큰 브랜드(1) 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY p.like_count desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+------------------------------------------------------------------------------+--------------------------+---------+-------+---------+----------+-------------+
| id | select_type | table | partitions | type | possible_keys                                                                | key                      | key_len | ref   | rows    | filtered | Extra       |
+----+-------------+-------+------------+------+------------------------------------------------------------------------------+--------------------------+---------+-------+---------+----------+-------------+
|  1 | SIMPLE      | p     | NULL       | ref  | idx_products_brand_price,idx_products_brand_created,idx_products_brand_likes | idx_products_brand_likes | 8       | const | 1420280 |    50.00 | Using where |
+----+-------------+-------+------------+------+------------------------------------------------------------------------------+--------------------------+---------+-------+---------+----------+-------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=219838 rows=20) (actual time=0.458..0.464 rows=20 loops=1)
    -> Filter: (p.deleted = false)  (cost=219838 rows=710140) (actual time=0.457..0.462 rows=20 loops=1)
        -> Index lookup on p using idx_products_brand_likes (brand_id=1)  (cost=219838 rows=1.42e+6) (actual time=0.454..0.458 rows=21 loops=1)


-- ==================== Q8_likes_small_brand: 작은 브랜드(200) 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=200 ORDER BY p.like_count desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+------------------------------------------------------------------------------+--------------------------+---------+-------+-------+----------+-------------+
| id | select_type | table | partitions | type | possible_keys                                                                | key                      | key_len | ref   | rows  | filtered | Extra       |
+----+-------------+-------+------------+------+------------------------------------------------------------------------------+--------------------------+---------+-------+-------+----------+-------------+
|  1 | SIMPLE      | p     | NULL       | ref  | idx_products_brand_price,idx_products_brand_created,idx_products_brand_likes | idx_products_brand_likes | 8       | const | 50556 |    50.00 | Using where |
+----+-------------+-------+------------+------+------------------------------------------------------------------------------+--------------------------+---------+-------+-------+----------+-------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=50955 rows=20) (actual time=1.55..1.56 rows=20 loops=1)
    -> Filter: (p.deleted = false)  (cost=50955 rows=25278) (actual time=1.55..1.55 rows=20 loops=1)
        -> Index lookup on p using idx_products_brand_likes (brand_id=200)  (cost=50955 rows=50556) (actual time=1.54..1.55 rows=21 loops=1)


-- ==================== Q9_like_count_page: 목록 1페이지 상품 20개의 좋아요 수 일괄 조회 (findLikeCounts)
SELECT p.id AS product_id, p.like_count AS total FROM products p WHERE p.id IN (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20);

-- EXPLAIN
+----+-------------+-------+------------+-------+---------------+---------+---------+------+------+----------+-------------+
| id | select_type | table | partitions | type  | possible_keys | key     | key_len | ref  | rows | filtered | Extra       |
+----+-------------+-------+------------+-------+---------------+---------+---------+------+------+----------+-------------+
|  1 | SIMPLE      | p     | NULL       | range | PRIMARY       | PRIMARY | 8       | NULL |   20 |   100.00 | Using where |
+----+-------------+-------+------------+-------+---------------+---------+---------+------+------+----------+-------------+
-- EXPLAIN ANALYZE
-> Filter: (p.id in (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20))  (cost=23.2 rows=20) (actual time=0.0393..0.0666 rows=20 loops=1)
    -> Index range scan on p using PRIMARY over (id = 1) OR (id = 2) OR (18 more)  (cost=23.2 rows=20) (actual time=0.0376..0.063 rows=20 loops=1)


-- ==================== Q10_like_count_detail: 상품 상세의 좋아요 수 (인기 상품 1)
SELECT p.id AS product_id, p.like_count AS total FROM products p WHERE p.id IN (1);

-- EXPLAIN
+----+-------------+-------+------------+-------+---------------+---------+---------+-------+------+----------+-------+
| id | select_type | table | partitions | type  | possible_keys | key     | key_len | ref   | rows | filtered | Extra |
+----+-------------+-------+------------+-------+---------------+---------+---------+-------+------+----------+-------+
|  1 | SIMPLE      | p     | NULL       | const | PRIMARY       | PRIMARY | 8       | const |    1 |   100.00 | NULL  |
+----+-------------+-------+------------+-------+---------------+---------+---------+-------+------+----------+-------+
-- EXPLAIN ANALYZE
-> Rows fetched before execution  (cost=0..0 rows=1) (actual time=88e-6..160e-6 rows=1 loops=1)


```

</details>

## API 측정: after-index-10m (2026-10-15 22:18)

- 환경: Linux 6.18.44-fc-v114 (x86_64) / CPU Intel(R) Xeon(R) Processor @ 2.10GHz, 2코어 / 메모리 8GB / MySQL 8.0.46-0ubuntu0.24.04.4 / Java: openjdk version "21.0.12.1" 2026-08-18
- 디스크 여유: 24G (MySQL 데이터 디렉터리) / buffer pool 128MB
- 앱: commerce-api local 프로필 + 명령행 인자 `--spring.profiles.active=local --spring.jpa.hibernate.ddl-auto=none --spring.jpa.show-sql=false --datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3306/loopers_perf --datasource.mysql-jpa.main.username=root --datasource.mysql-jpa.main.password=*** --server.port=8080 `
  개선 후(after) 측정도 같은 계정(root)·옵션으로 실행해야 비교할 수 있다.
- 데이터 보존 확인: loopers_perf.products 시작 전 10000000 / 종료 후 10000000
- 방식: curl 순차 1개, X-USER-ID: 1. 워밍업 후 측정 요청의 time_total 백분위(ms, nearest-rank).
  첫 요청이 99999999ms를 넘으면 워밍업 1회 + 측정 3회로 줄인다. 표의 '표본'이 요청별 실제 측정 수다.
- 요청당 SQL: 측정 직전 events_statements_summary_by_digest를 비우고, 측정 후 SCHEMA_NAME=loopers_perf(앱 커넥션) 실행 수를 요청 수로 나눈 값
- 응답 스냅샷: `docs/week4/perf-results/after-index-10m/api-*.tsv` (마지막 응답의 id·price·likeCount)
- 데이터: users 500000 / brands 200 (삭제 5) / products 10000000 (미삭제 9293588) / product_likes 9969594
- 테이블 크기(데이터+인덱스): brands 0MB(인덱스 0MB) / product_likes 957MB(인덱스 522MB) / products 2504MB(인덱스 1694MB) / users 15MB(인덱스 0MB)
- 인덱스: brands.PRIMARY(id), product_likes.idx_product_likes_product(product_id), product_likes.PRIMARY(id), product_likes.uk_product_likes_user_product(user_id,product_id), products.idx_products_brand_created(brand_id,created_at), products.idx_products_brand_likes(brand_id,like_count,id), products.idx_products_brand_price(brand_id,price,id), products.idx_products_created(created_at,id), products.idx_products_likes(like_count,id), products.idx_products_price(price,id), products.PRIMARY(id), users.PRIMARY(id)

| 요청 | 설명 | 경로 | 워밍업 | 표본 | p50 | p95 | p99 | 최대 | 200 응답 | 요청당 SQL | 요청당 SELECT | 요청당 SQL 시간(ms) |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| R1 | 브랜드 1 가격순 1페이지 (과제 측정 대상) | `/api/v1/products?brandId=1&sort=price_asc&page=0&size=20` | 3 | 30 | 24.6 | 43.8 | 51.3 | 51.3 | 30/30 | 14.0 | 4.0 | 3.1 |
| R2 | 브랜드 200 가격순 1페이지 | `/api/v1/products?brandId=200&sort=price_asc&page=0&size=20` | 3 | 30 | 19.6 | 34.6 | 37.4 | 37.4 | 30/30 | 14.0 | 4.0 | 3.4 |
| R3 | 브랜드 200 좋아요순 1페이지 | `/api/v1/products?brandId=200&sort=likes_desc&page=0&size=20` | 3 | 30 | 15.3 | 28.5 | 33.4 | 33.4 | 30/30 | 14.0 | 4.0 | 3.5 |
| R4 | 상품 1 상세 | `/api/v1/products/1` | 3 | 30 | 12.3 | 19.9 | 22.4 | 22.4 | 30/30 | 14.0 | 4.0 | 1.3 |

<details><summary>요청별 SQL digest</summary>

```text
-- R1 /api/v1/products?brandId=1&sort=price_asc&page=0&size=20 (요청 30회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
120	7.45	SET autocommit = ?
60	4.66	SET SESSION TRANSACTION READ ONLY
60	4.37	COMMIT
60	3.04	SET SESSION TRANSACTION READ WRITE
30	45.94	SELECT p . * FROM products p WHERE p . deleted = FALSE AND p . brand_id = ? ORDER BY p . price ASC , p . id DESC LIMIT ? OFFSET ?
30	10.49	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
30	8.94	SELECT pje1_0 . id , pje1_0 . like_count FROM products pje1_0 WHERE pje1_0 . id IN (...)
30	6.89	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?

-- R2 /api/v1/products?brandId=200&sort=price_asc&page=0&size=20 (요청 30회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
120	6.53	SET autocommit = ?
60	7.14	SET SESSION TRANSACTION READ ONLY
60	3.94	COMMIT
60	2.83	SET SESSION TRANSACTION READ WRITE
30	53.22	SELECT p . * FROM products p WHERE p . deleted = FALSE AND p . brand_id = ? ORDER BY p . price ASC , p . id DESC LIMIT ? OFFSET ?
30	10.75	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
30	9.87	SELECT pje1_0 . id , pje1_0 . like_count FROM products pje1_0 WHERE pje1_0 . id IN (...)
30	6.90	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?

-- R3 /api/v1/products?brandId=200&sort=likes_desc&page=0&size=20 (요청 30회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
120	7.57	SET autocommit = ?
60	5.86	SET SESSION TRANSACTION READ ONLY
60	4.42	COMMIT
60	3.73	SET SESSION TRANSACTION READ WRITE
30	54.86	SELECT p . * FROM products p WHERE p . deleted = FALSE AND p . brand_id = ? ORDER BY p . like_count DESC , p . id DESC LIMIT ? OFFSET ?
30	13.91	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
30	10.38	SELECT pje1_0 . id , pje1_0 . like_count FROM products pje1_0 WHERE pje1_0 . id IN (...)
30	5.52	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?

-- R4 /api/v1/products/1 (요청 30회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
120	5.28	SET autocommit = ?
60	4.56	SET SESSION TRANSACTION READ ONLY
60	3.28	COMMIT
60	2.00	SET SESSION TRANSACTION READ WRITE
30	6.83	SELECT pje1_0 . id , pje1_0 . brand_id , pje1_0 . created_at , pje1_0 . deleted , pje1_0 . like_count , pje1_0 . name , pje1_0 . price , pje1_0 . stock FROM pro
30	6.15	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?
30	6.06	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
30	4.84	SELECT pje1_0 . id , pje1_0 . like_count FROM products pje1_0 WHERE pje1_0 . id IN (...)

```

</details>


## API 측정: cache-off (2026-10-16 00:12)

- 환경: Linux 6.18.44-fc-v114 (x86_64) / CPU Intel(R) Xeon(R) Processor @ 2.10GHz, 2코어 / 메모리 8GB / MySQL 8.0.46-0ubuntu0.24.04.4 / Java: openjdk version "21.0.12.1" 2026-08-18
- 디스크 여유: 27G (MySQL 데이터 디렉터리) / buffer pool 128MB
- 앱: commerce-api local 프로필 + 명령행 인자 `--spring.profiles.active=local --spring.jpa.hibernate.ddl-auto=none --spring.jpa.show-sql=false --datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3306/loopers_perf --datasource.mysql-jpa.main.username=root --datasource.mysql-jpa.main.password=*** --server.port=8080 `
  개선 후(after) 측정도 같은 계정(root)·옵션으로 실행해야 비교할 수 있다.
- 데이터 보존 확인: loopers_perf.products 시작 전 100000 / 종료 후 100000
- 방식: curl 순차 1개, X-USER-ID: 1. 워밍업 후 측정 요청의 time_total 백분위(ms, nearest-rank).
  첫 요청이 5000ms를 넘으면 워밍업 1회 + 측정 3회로 줄인다. 표의 '표본'이 요청별 실제 측정 수다.
- 요청당 SQL: 측정 직전 events_statements_summary_by_digest를 비우고, 측정 후 SCHEMA_NAME=loopers_perf(앱 커넥션) 실행 수를 요청 수로 나눈 값
- 응답 스냅샷: `docs/week4/perf-results/cache-off/api-*.tsv` (마지막 응답의 id·price·likeCount)
- 데이터: users 50000 / brands 200 (삭제 5) / products 100000 (미삭제 92859) / product_likes 974368
- 테이블 크기(데이터+인덱스): brands 0MB(인덱스 0MB) / product_likes 95MB(인덱스 52MB) / products 27MB(인덱스 18MB) / users 2MB(인덱스 0MB)
- 인덱스: brands.PRIMARY(id), product_likes.idx_product_likes_product(product_id), product_likes.PRIMARY(id), product_likes.uk_product_likes_user_product(user_id,product_id), products.idx_products_brand_created(brand_id,created_at), products.idx_products_brand_likes(brand_id,like_count,id), products.idx_products_brand_price(brand_id,price,id), products.idx_products_created(created_at,id), products.idx_products_likes(like_count,id), products.idx_products_price(price,id), products.PRIMARY(id), users.PRIMARY(id)

| 요청 | 설명 | 경로 | 워밍업 | 표본 | p50 | p95 | p99 | 최대 | 200 응답 | 요청당 SQL | 요청당 SELECT | 요청당 SQL 시간(ms) |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| R1 | 브랜드 1 가격순 1페이지 (과제 측정 대상) | `/api/v1/products?brandId=1&sort=price_asc&page=0&size=20` | 20 | 200 | 14.9 | 28.6 | 37.6 | 41.8 | 200/200 | 14.0 | 4.0 | 2.4 |
| R2 | 브랜드 200 가격순 1페이지 | `/api/v1/products?brandId=200&sort=price_asc&page=0&size=20` | 20 | 200 | 10.2 | 18.2 | 24.0 | 28.9 | 200/200 | 14.0 | 4.0 | 1.8 |
| R3 | 브랜드 200 좋아요순 1페이지 | `/api/v1/products?brandId=200&sort=likes_desc&page=0&size=20` | 20 | 200 | 8.2 | 14.0 | 20.4 | 35.3 | 200/200 | 14.0 | 4.0 | 1.5 |
| R4 | 상품 1 상세 | `/api/v1/products/1` | 20 | 200 | 5.9 | 10.6 | 14.4 | 14.9 | 200/200 | 14.0 | 4.0 | 1.1 |

<details><summary>요청별 SQL digest</summary>

```text
-- R1 /api/v1/products?brandId=1&sort=price_asc&page=0&size=20 (요청 200회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
800	45.23	SET autocommit = ?
400	25.07	COMMIT
400	23.30	SET SESSION TRANSACTION READ ONLY
400	13.84	SET SESSION TRANSACTION READ WRITE
200	203.14	SELECT p . * FROM products p WHERE p . deleted = FALSE AND p . brand_id = ? ORDER BY p . price ASC , p . id DESC LIMIT ? OFFSET ?
200	69.94	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
200	58.29	SELECT pje1_0 . id , pje1_0 . like_count FROM products pje1_0 WHERE pje1_0 . id IN (...)
200	36.68	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?

-- R2 /api/v1/products?brandId=200&sort=price_asc&page=0&size=20 (요청 200회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
800	44.49	SET autocommit = ?
400	24.74	SET SESSION TRANSACTION READ ONLY
400	23.90	COMMIT
400	15.95	SET SESSION TRANSACTION READ WRITE
200	105.84	SELECT p . * FROM products p WHERE p . deleted = FALSE AND p . brand_id = ? ORDER BY p . price ASC , p . id DESC LIMIT ? OFFSET ?
200	56.80	SELECT pje1_0 . id , pje1_0 . like_count FROM products pje1_0 WHERE pje1_0 . id IN (...)
200	47.08	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
200	38.15	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?

-- R3 /api/v1/products?brandId=200&sort=likes_desc&page=0&size=20 (요청 200회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
800	38.45	SET autocommit = ?
400	25.23	SET SESSION TRANSACTION READ ONLY
400	21.46	COMMIT
400	15.03	SET SESSION TRANSACTION READ WRITE
200	74.11	SELECT p . * FROM products p WHERE p . deleted = FALSE AND p . brand_id = ? ORDER BY p . like_count DESC , p . id DESC LIMIT ? OFFSET ?
200	53.13	SELECT pje1_0 . id , pje1_0 . like_count FROM products pje1_0 WHERE pje1_0 . id IN (...)
200	40.65	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
200	35.56	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?

-- R4 /api/v1/products/1 (요청 200회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
800	42.55	SET autocommit = ?
400	22.45	SET SESSION TRANSACTION READ ONLY
400	16.07	COMMIT
400	11.99	SET SESSION TRANSACTION READ WRITE
200	33.59	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
200	30.72	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?
200	28.39	SELECT pje1_0 . id , pje1_0 . brand_id , pje1_0 . created_at , pje1_0 . deleted , pje1_0 . like_count , pje1_0 . name , pje1_0 . price , pje1_0 . stock FROM pro
200	27.28	SELECT pje1_0 . id , pje1_0 . like_count FROM products pje1_0 WHERE pje1_0 . id IN (...)

```

</details>

## API 측정: cache-hit (2026-10-16 00:13)

- 환경: Linux 6.18.44-fc-v114 (x86_64) / CPU Intel(R) Xeon(R) Processor @ 2.10GHz, 2코어 / 메모리 8GB / MySQL 8.0.46-0ubuntu0.24.04.4 / Java: openjdk version "21.0.12.1" 2026-08-18
- 디스크 여유: 27G (MySQL 데이터 디렉터리) / buffer pool 128MB
- 앱: commerce-api local 프로필 + 명령행 인자 `--spring.profiles.active=local --spring.jpa.hibernate.ddl-auto=none --spring.jpa.show-sql=false --datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3306/loopers_perf --datasource.mysql-jpa.main.username=root --datasource.mysql-jpa.main.password=*** --server.port=8080 `
  개선 후(after) 측정도 같은 계정(root)·옵션으로 실행해야 비교할 수 있다.
- 데이터 보존 확인: loopers_perf.products 시작 전 100000 / 종료 후 100000
- 방식: curl 순차 1개, X-USER-ID: 1. 워밍업 후 측정 요청의 time_total 백분위(ms, nearest-rank).
  첫 요청이 5000ms를 넘으면 워밍업 1회 + 측정 3회로 줄인다. 표의 '표본'이 요청별 실제 측정 수다.
- 요청당 SQL: 측정 직전 events_statements_summary_by_digest를 비우고, 측정 후 SCHEMA_NAME=loopers_perf(앱 커넥션) 실행 수를 요청 수로 나눈 값
- 응답 스냅샷: `docs/week4/perf-results/cache-hit/api-*.tsv` (마지막 응답의 id·price·likeCount)
- 캐시 모드: hit (워밍업이 채운 캐시를 읽음)
- Redis 상태: 시작 전 version=(없음) keys=0 / 종료 후 version=(없음) keys=3 (목록 키 수는 SCAN으로 셈)
- 요청당 Redis 명령: 측정 구간 전후 INFO commandstats 차이 ÷ 표본 (도구가 보낸 INCR·INFO·SCAN 제외)
- 데이터: users 50000 / brands 200 (삭제 5) / products 100000 (미삭제 92859) / product_likes 974368
- 테이블 크기(데이터+인덱스): brands 0MB(인덱스 0MB) / product_likes 95MB(인덱스 52MB) / products 27MB(인덱스 18MB) / users 2MB(인덱스 0MB)
- 인덱스: brands.PRIMARY(id), product_likes.idx_product_likes_product(product_id), product_likes.PRIMARY(id), product_likes.uk_product_likes_user_product(user_id,product_id), products.idx_products_brand_created(brand_id,created_at), products.idx_products_brand_likes(brand_id,like_count,id), products.idx_products_brand_price(brand_id,price,id), products.idx_products_created(created_at,id), products.idx_products_likes(like_count,id), products.idx_products_price(price,id), products.PRIMARY(id), users.PRIMARY(id)

| 요청 | 설명 | 경로 | 워밍업 | 표본 | p50 | p95 | p99 | 최대 | 200 응답 | 요청당 SQL | 요청당 SELECT | 요청당 SQL 시간(ms) | 요청당 목록 검색 SELECT | 요청당 Redis 명령 |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| R1 | 브랜드 1 가격순 1페이지 (과제 측정 대상) | `/api/v1/products?brandId=1&sort=price_asc&page=0&size=20` | 20 | 200 | 13.1 | 25.9 | 33.0 | 50.5 | 200/200 | 12.0 | 2.0 | 1.2 | 0.0 | GET 2.0 / SET 0.0 / INCR 0.0 / 기타 0.0 |
| R2 | 브랜드 200 가격순 1페이지 | `/api/v1/products?brandId=200&sort=price_asc&page=0&size=20` | 20 | 200 | 9.1 | 17.0 | 21.0 | 56.7 | 200/200 | 12.0 | 2.0 | 1.1 | 0.0 | GET 2.0 / SET 0.0 / INCR 0.0 / 기타 0.0 |
| R3 | 브랜드 200 좋아요순 1페이지 | `/api/v1/products?brandId=200&sort=likes_desc&page=0&size=20` | 20 | 200 | 6.3 | 11.6 | 16.3 | 31.2 | 200/200 | 12.0 | 2.0 | 1.0 | 0.0 | GET 2.0 / SET 0.0 / INCR 0.0 / 기타 0.0 |
| R4 | 상품 1 상세 | `/api/v1/products/1` | 20 | 200 | 6.1 | 11.7 | 18.7 | 30.0 | 200/200 | 14.0 | 4.0 | 1.1 | 0.0 | GET 0.0 / SET 0.0 / INCR 0.0 / 기타 0.0 |

<details><summary>요청별 SQL digest</summary>

```text
-- R1 /api/v1/products?brandId=1&sort=price_asc&page=0&size=20 (요청 200회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
800	42.03	SET autocommit = ?
400	30.43	SET SESSION TRANSACTION READ ONLY
400	25.16	COMMIT
400	15.68	SET SESSION TRANSACTION READ WRITE
200	80.80	SELECT pje1_0 . id , pje1_0 . brand_id , pje1_0 . created_at , pje1_0 . deleted , pje1_0 . like_count , pje1_0 . name , pje1_0 . price , pje1_0 . stock FROM pro
200	37.69	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?

-- R2 /api/v1/products?brandId=200&sort=price_asc&page=0&size=20 (요청 200회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
800	39.86	SET autocommit = ?
400	30.17	COMMIT
400	29.97	SET SESSION TRANSACTION READ ONLY
400	13.67	SET SESSION TRANSACTION READ WRITE
200	72.83	SELECT pje1_0 . id , pje1_0 . brand_id , pje1_0 . created_at , pje1_0 . deleted , pje1_0 . like_count , pje1_0 . name , pje1_0 . price , pje1_0 . stock FROM pro
200	35.45	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?

-- R3 /api/v1/products?brandId=200&sort=likes_desc&page=0&size=20 (요청 200회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
800	36.85	SET autocommit = ?
400	23.40	SET SESSION TRANSACTION READ ONLY
400	19.11	COMMIT
400	12.62	SET SESSION TRANSACTION READ WRITE
200	73.80	SELECT pje1_0 . id , pje1_0 . brand_id , pje1_0 . created_at , pje1_0 . deleted , pje1_0 . like_count , pje1_0 . name , pje1_0 . price , pje1_0 . stock FROM pro
200	31.34	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?

-- R4 /api/v1/products/1 (요청 200회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
800	39.21	SET autocommit = ?
400	20.35	SET SESSION TRANSACTION READ ONLY
400	19.29	SET SESSION TRANSACTION READ WRITE
400	16.75	COMMIT
200	36.12	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?
200	35.80	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
200	29.71	SELECT pje1_0 . id , pje1_0 . brand_id , pje1_0 . created_at , pje1_0 . deleted , pje1_0 . like_count , pje1_0 . name , pje1_0 . price , pje1_0 . stock FROM pro
200	29.47	SELECT pje1_0 . id , pje1_0 . like_count FROM products pje1_0 WHERE pje1_0 . id IN (...)

```

</details>

## API 측정: cache-miss (2026-10-16 00:13)

- 환경: Linux 6.18.44-fc-v114 (x86_64) / CPU Intel(R) Xeon(R) Processor @ 2.10GHz, 2코어 / 메모리 8GB / MySQL 8.0.46-0ubuntu0.24.04.4 / Java: openjdk version "21.0.12.1" 2026-08-18
- 디스크 여유: 27G (MySQL 데이터 디렉터리) / buffer pool 128MB
- 앱: commerce-api local 프로필 + 명령행 인자 `--spring.profiles.active=local --spring.jpa.hibernate.ddl-auto=none --spring.jpa.show-sql=false --datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3306/loopers_perf --datasource.mysql-jpa.main.username=root --datasource.mysql-jpa.main.password=*** --server.port=8080 `
  개선 후(after) 측정도 같은 계정(root)·옵션으로 실행해야 비교할 수 있다.
- 데이터 보존 확인: loopers_perf.products 시작 전 100000 / 종료 후 100000
- 방식: curl 순차 1개, X-USER-ID: 1. 워밍업 후 측정 요청의 time_total 백분위(ms, nearest-rank).
  첫 요청이 5000ms를 넘으면 워밍업 1회 + 측정 3회로 줄인다. 표의 '표본'이 요청별 실제 측정 수다.
- 요청당 SQL: 측정 직전 events_statements_summary_by_digest를 비우고, 측정 후 SCHEMA_NAME=loopers_perf(앱 커넥션) 실행 수를 요청 수로 나눈 값
- 응답 스냅샷: `docs/week4/perf-results/cache-miss/api-*.tsv` (마지막 응답의 id·price·likeCount)
- 캐시 모드: miss (측정·워밍업 요청마다 직전에 INCR products:list:version, 요청 시간 밖)
- Redis 상태: 시작 전 version=(없음) keys=3 / 종료 후 version=660 keys=663 (목록 키 수는 SCAN으로 셈)
- 요청당 Redis 명령: 측정 구간 전후 INFO commandstats 차이 ÷ 표본 (도구가 보낸 INCR·INFO·SCAN 제외)
- 데이터: users 50000 / brands 200 (삭제 5) / products 100000 (미삭제 92859) / product_likes 974368
- 테이블 크기(데이터+인덱스): brands 0MB(인덱스 0MB) / product_likes 95MB(인덱스 52MB) / products 27MB(인덱스 18MB) / users 2MB(인덱스 0MB)
- 인덱스: brands.PRIMARY(id), product_likes.idx_product_likes_product(product_id), product_likes.PRIMARY(id), product_likes.uk_product_likes_user_product(user_id,product_id), products.idx_products_brand_created(brand_id,created_at), products.idx_products_brand_likes(brand_id,like_count,id), products.idx_products_brand_price(brand_id,price,id), products.idx_products_created(created_at,id), products.idx_products_likes(like_count,id), products.idx_products_price(price,id), products.PRIMARY(id), users.PRIMARY(id)

| 요청 | 설명 | 경로 | 워밍업 | 표본 | p50 | p95 | p99 | 최대 | 200 응답 | 요청당 SQL | 요청당 SELECT | 요청당 SQL 시간(ms) | 요청당 목록 검색 SELECT | 요청당 Redis 명령 |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| R1 | 브랜드 1 가격순 1페이지 (과제 측정 대상) | `/api/v1/products?brandId=1&sort=price_asc&page=0&size=20` | 20 | 200 | 21.8 | 38.3 | 49.5 | 129.3 | 200/200 | 15.0 | 5.0 | 3.5 | 1.0 | GET 2.0 / SET 1.0 / INCR 0.0 / 기타 0.0 |
| R2 | 브랜드 200 가격순 1페이지 | `/api/v1/products?brandId=200&sort=price_asc&page=0&size=20` | 20 | 200 | 13.0 | 22.6 | 32.7 | 41.9 | 200/200 | 15.0 | 5.0 | 2.1 | 1.0 | GET 2.0 / SET 1.0 / INCR 0.0 / 기타 0.0 |
| R3 | 브랜드 200 좋아요순 1페이지 | `/api/v1/products?brandId=200&sort=likes_desc&page=0&size=20` | 20 | 200 | 10.9 | 19.0 | 24.6 | 30.2 | 200/200 | 15.0 | 5.0 | 2.1 | 1.0 | GET 2.0 / SET 1.0 / INCR 0.0 / 기타 0.0 |

<details><summary>요청별 SQL digest</summary>

```text
-- R1 /api/v1/products?brandId=1&sort=price_asc&page=0&size=20 (요청 200회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
800	48.41	SET autocommit = ?
400	130.45	COMMIT
400	39.08	SET SESSION TRANSACTION READ ONLY
400	17.15	SET SESSION TRANSACTION READ WRITE
200	240.22	SELECT p . * FROM products p WHERE p . deleted = FALSE AND p . brand_id = ? ORDER BY p . price ASC , p . id DESC LIMIT ? OFFSET ?
200	67.45	SELECT pje1_0 . id , pje1_0 . brand_id , pje1_0 . created_at , pje1_0 . deleted , pje1_0 . like_count , pje1_0 . name , pje1_0 . price , pje1_0 . stock FROM pro
200	59.35	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
200	57.06	SELECT pje1_0 . id , pje1_0 . like_count FROM products pje1_0 WHERE pje1_0 . id IN (...)
200	46.93	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?

-- R2 /api/v1/products?brandId=200&sort=price_asc&page=0&size=20 (요청 200회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
800	42.74	SET autocommit = ?
400	28.37	SET SESSION TRANSACTION READ ONLY
400	25.51	COMMIT
400	13.33	SET SESSION TRANSACTION READ WRITE
200	112.12	SELECT p . * FROM products p WHERE p . deleted = FALSE AND p . brand_id = ? ORDER BY p . price ASC , p . id DESC LIMIT ? OFFSET ?
200	58.31	SELECT pje1_0 . id , pje1_0 . brand_id , pje1_0 . created_at , pje1_0 . deleted , pje1_0 . like_count , pje1_0 . name , pje1_0 . price , pje1_0 . stock FROM pro
200	53.13	SELECT pje1_0 . id , pje1_0 . like_count FROM products pje1_0 WHERE pje1_0 . id IN (...)
200	48.29	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
200	41.32	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?

-- R3 /api/v1/products?brandId=200&sort=likes_desc&page=0&size=20 (요청 200회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
800	55.62	SET autocommit = ?
400	29.03	SET SESSION TRANSACTION READ ONLY
400	26.23	COMMIT
400	17.53	SET SESSION TRANSACTION READ WRITE
200	96.45	SELECT p . * FROM products p WHERE p . deleted = FALSE AND p . brand_id = ? ORDER BY p . like_count DESC , p . id DESC LIMIT ? OFFSET ?
200	58.35	SELECT pje1_0 . id , pje1_0 . brand_id , pje1_0 . created_at , pje1_0 . deleted , pje1_0 . like_count , pje1_0 . name , pje1_0 . price , pje1_0 . stock FROM pro
200	54.99	SELECT pje1_0 . id , pje1_0 . like_count FROM products pje1_0 WHERE pje1_0 . id IN (...)
200	49.49	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?
200	38.80	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)

```

</details>

## 캐시 장애 측정: cache-redis-stop (Redis stop, 2026-10-16 00:14)

- 환경: Linux 6.18.44-fc-v114 (x86_64) / CPU Intel(R) Xeon(R) Processor @ 2.10GHz, 2코어 / 메모리 8GB / MySQL 8.0.46-0ubuntu0.24.04.4 / Java: openjdk version "21.0.12.1" 2026-08-18
- 디스크 여유: 27G (MySQL 데이터 디렉터리) / buffer pool 128MB
- 앱: commerce-api local 프로필 + 명령행 인자 `--spring.profiles.active=local --spring.jpa.hibernate.ddl-auto=none --spring.jpa.show-sql=false --datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3306/loopers_perf --datasource.mysql-jpa.main.username=root --datasource.mysql-jpa.main.password=*** --server.port=8080 `
- 방식: 정상 상태에서 R1 1회로 캐시를 채운 뒤 `/tmp/claude-0/redis-ctl.sh stop` → R1을 워밍업 없이 20회 순차 요청 → `/tmp/claude-0/redis-ctl.sh start`
- 응답 비교 기준: `perf-results/cache-off/api-R1.tsv` (id·price·likeCount 순서, 빈 목록·가격 0이면 다름)
- Redis 상태: 장애 전 version=660 keys=661 / 복구 후 version=(없음) keys=1
- 복구: Redis 응답까지 0초, 9번째 요청 후 재채움 (200, 0.070621초, 키 products:list:v0:brand=1:sort=price_asc:page=0:size=20)
- 앱 로그의 캐시 경고("상품 목록 캐시") 줄 수: 28
- 데이터: users 50000 / brands 200 (삭제 5) / products 100000 (미삭제 92859) / product_likes 974368
- 테이블 크기(데이터+인덱스): brands 0MB(인덱스 0MB) / product_likes 95MB(인덱스 52MB) / products 27MB(인덱스 18MB) / users 2MB(인덱스 0MB)
- 인덱스: brands.PRIMARY(id), product_likes.idx_product_likes_product(product_id), product_likes.PRIMARY(id), product_likes.uk_product_likes_user_product(user_id,product_id), products.idx_products_brand_created(brand_id,created_at), products.idx_products_brand_likes(brand_id,like_count,id), products.idx_products_brand_price(brand_id,price,id), products.idx_products_created(created_at,id), products.idx_products_likes(like_count,id), products.idx_products_price(price,id), products.PRIMARY(id), users.PRIMARY(id)

| 상태 | 요청 | 200 응답 | 기준과 같은 응답 | p50 | p95 | p99 | 최대 | 응답 코드 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Redis stop | 20 | 20 | 20 | 1036.6 | 1047.8 | 1056.6 | 1056.6 | 200×20  |

## 캐시 장애 측정: cache-redis-pause (Redis pause, 2026-10-16 00:15)

- 환경: Linux 6.18.44-fc-v114 (x86_64) / CPU Intel(R) Xeon(R) Processor @ 2.10GHz, 2코어 / 메모리 8GB / MySQL 8.0.46-0ubuntu0.24.04.4 / Java: openjdk version "21.0.12.1" 2026-08-18
- 디스크 여유: 27G (MySQL 데이터 디렉터리) / buffer pool 128MB
- 앱: commerce-api local 프로필 + 명령행 인자 `--spring.profiles.active=local --spring.jpa.hibernate.ddl-auto=none --spring.jpa.show-sql=false --datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3306/loopers_perf --datasource.mysql-jpa.main.username=root --datasource.mysql-jpa.main.password=*** --server.port=8080 `
- 방식: 정상 상태에서 R1 1회로 캐시를 채운 뒤 `/tmp/claude-0/redis-ctl.sh pause` → R1을 워밍업 없이 20회 순차 요청 → `/tmp/claude-0/redis-ctl.sh unpause`
- 응답 비교 기준: `perf-results/cache-off/api-R1.tsv` (id·price·likeCount 순서, 빈 목록·가격 0이면 다름)
- Redis 상태: 장애 전 version=(없음) keys=1 / 복구 후 version=(없음) keys=1
- 복구: Redis 응답까지 0초, 1번째 요청 후 재채움 (200, 0.028070초, 키 products:list:v0:brand=1:sort=price_asc:page=0:size=20)
- 앱 로그의 캐시 경고("상품 목록 캐시") 줄 수: 20
- 데이터: users 50000 / brands 200 (삭제 5) / products 100000 (미삭제 92859) / product_likes 974368
- 테이블 크기(데이터+인덱스): brands 0MB(인덱스 0MB) / product_likes 95MB(인덱스 52MB) / products 27MB(인덱스 18MB) / users 2MB(인덱스 0MB)
- 인덱스: brands.PRIMARY(id), product_likes.idx_product_likes_product(product_id), product_likes.PRIMARY(id), product_likes.uk_product_likes_user_product(user_id,product_id), products.idx_products_brand_created(brand_id,created_at), products.idx_products_brand_likes(brand_id,like_count,id), products.idx_products_brand_price(brand_id,price,id), products.idx_products_created(created_at,id), products.idx_products_likes(like_count,id), products.idx_products_price(price,id), products.PRIMARY(id), users.PRIMARY(id)

| 상태 | 요청 | 200 응답 | 기준과 같은 응답 | p50 | p95 | p99 | 최대 | 응답 코드 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Redis pause | 20 | 20 | 20 | 1038.3 | 1075.0 | 1087.3 | 1087.3 | 200×20  |

## API 측정: cache-off-10m (2026-10-16 00:25)

- 환경: Linux 6.18.44-fc-v114 (x86_64) / CPU Intel(R) Xeon(R) Processor @ 2.10GHz, 2코어 / 메모리 8GB / MySQL 8.0.46-0ubuntu0.24.04.4 / Java: openjdk version "21.0.12.1" 2026-08-18
- 디스크 여유: 24G (MySQL 데이터 디렉터리) / buffer pool 128MB
- 앱: commerce-api local 프로필 + 명령행 인자 `--spring.profiles.active=local --spring.jpa.hibernate.ddl-auto=none --spring.jpa.show-sql=false --datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3306/loopers_perf --datasource.mysql-jpa.main.username=root --datasource.mysql-jpa.main.password=*** --server.port=8080 `
  개선 후(after) 측정도 같은 계정(root)·옵션으로 실행해야 비교할 수 있다.
- 데이터 보존 확인: loopers_perf.products 시작 전 10000000 / 종료 후 10000000
- 방식: curl 순차 1개, X-USER-ID: 1. 워밍업 후 측정 요청의 time_total 백분위(ms, nearest-rank).
  첫 요청이 99999999ms를 넘으면 워밍업 1회 + 측정 3회로 줄인다. 표의 '표본'이 요청별 실제 측정 수다.
- 요청당 SQL: 측정 직전 events_statements_summary_by_digest를 비우고, 측정 후 SCHEMA_NAME=loopers_perf(앱 커넥션) 실행 수를 요청 수로 나눈 값
- 응답 스냅샷: `docs/week4/perf-results/cache-off-10m/api-*.tsv` (마지막 응답의 id·price·likeCount)
- 데이터: users 500000 / brands 200 (삭제 5) / products 10000000 (미삭제 9293588) / product_likes 9969594
- 테이블 크기(데이터+인덱스): brands 0MB(인덱스 0MB) / product_likes 957MB(인덱스 522MB) / products 2504MB(인덱스 1694MB) / users 15MB(인덱스 0MB)
- 인덱스: brands.PRIMARY(id), product_likes.idx_product_likes_product(product_id), product_likes.PRIMARY(id), product_likes.uk_product_likes_user_product(user_id,product_id), products.idx_products_brand_created(brand_id,created_at), products.idx_products_brand_likes(brand_id,like_count,id), products.idx_products_brand_price(brand_id,price,id), products.idx_products_created(created_at,id), products.idx_products_likes(like_count,id), products.idx_products_price(price,id), products.PRIMARY(id), users.PRIMARY(id)

| 요청 | 설명 | 경로 | 워밍업 | 표본 | p50 | p95 | p99 | 최대 | 200 응답 | 요청당 SQL | 요청당 SELECT | 요청당 SQL 시간(ms) |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| R1 | 브랜드 1 가격순 1페이지 (과제 측정 대상) | `/api/v1/products?brandId=1&sort=price_asc&page=0&size=20` | 3 | 30 | 24.4 | 47.1 | 51.1 | 51.1 | 30/30 | 14.0 | 4.0 | 3.2 |
| R2 | 브랜드 200 가격순 1페이지 | `/api/v1/products?brandId=200&sort=price_asc&page=0&size=20` | 3 | 30 | 18.3 | 29.7 | 33.8 | 33.8 | 30/30 | 14.0 | 4.0 | 2.8 |
| R3 | 브랜드 200 좋아요순 1페이지 | `/api/v1/products?brandId=200&sort=likes_desc&page=0&size=20` | 3 | 30 | 17.4 | 25.6 | 26.7 | 26.7 | 30/30 | 14.0 | 4.0 | 2.9 |
| R4 | 상품 1 상세 | `/api/v1/products/1` | 3 | 30 | 13.3 | 31.1 | 33.7 | 33.7 | 30/30 | 14.0 | 4.0 | 1.4 |

<details><summary>요청별 SQL digest</summary>

```text
-- R1 /api/v1/products?brandId=1&sort=price_asc&page=0&size=20 (요청 30회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
120	5.89	SET autocommit = ?
60	6.86	SET SESSION TRANSACTION READ ONLY
60	4.36	COMMIT
60	2.54	SET SESSION TRANSACTION READ WRITE
30	45.23	SELECT p . * FROM products p WHERE p . deleted = FALSE AND p . brand_id = ? ORDER BY p . price ASC , p . id DESC LIMIT ? OFFSET ?
30	11.47	SELECT pje1_0 . id , pje1_0 . like_count FROM products pje1_0 WHERE pje1_0 . id IN (...)
30	11.41	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
30	7.82	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?

-- R2 /api/v1/products?brandId=200&sort=price_asc&page=0&size=20 (요청 30회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
120	5.54	SET autocommit = ?
60	4.38	SET SESSION TRANSACTION READ ONLY
60	3.55	COMMIT
60	2.04	SET SESSION TRANSACTION READ WRITE
30	47.93	SELECT p . * FROM products p WHERE p . deleted = FALSE AND p . brand_id = ? ORDER BY p . price ASC , p . id DESC LIMIT ? OFFSET ?
30	8.11	SELECT pje1_0 . id , pje1_0 . like_count FROM products pje1_0 WHERE pje1_0 . id IN (...)
30	7.37	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
30	6.03	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?

-- R3 /api/v1/products?brandId=200&sort=likes_desc&page=0&size=20 (요청 30회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
120	5.20	SET autocommit = ?
60	4.08	SET SESSION TRANSACTION READ ONLY
60	3.44	COMMIT
60	1.90	SET SESSION TRANSACTION READ WRITE
30	50.19	SELECT p . * FROM products p WHERE p . deleted = FALSE AND p . brand_id = ? ORDER BY p . like_count DESC , p . id DESC LIMIT ? OFFSET ?
30	10.04	SELECT pje1_0 . id , pje1_0 . like_count FROM products pje1_0 WHERE pje1_0 . id IN (...)
30	8.04	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
30	5.08	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?

-- R4 /api/v1/products/1 (요청 30회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
120	8.76	SET autocommit = ?
60	3.95	SET SESSION TRANSACTION READ ONLY
60	3.52	COMMIT
60	2.20	SET SESSION TRANSACTION READ WRITE
30	7.86	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
30	5.47	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?
30	4.76	SELECT pje1_0 . id , pje1_0 . brand_id , pje1_0 . created_at , pje1_0 . deleted , pje1_0 . like_count , pje1_0 . name , pje1_0 . price , pje1_0 . stock FROM pro
30	4.28	SELECT pje1_0 . id , pje1_0 . like_count FROM products pje1_0 WHERE pje1_0 . id IN (...)

```

</details>

## API 측정: cache-hit-10m (2026-10-16 00:25)

- 환경: Linux 6.18.44-fc-v114 (x86_64) / CPU Intel(R) Xeon(R) Processor @ 2.10GHz, 2코어 / 메모리 8GB / MySQL 8.0.46-0ubuntu0.24.04.4 / Java: openjdk version "21.0.12.1" 2026-08-18
- 디스크 여유: 24G (MySQL 데이터 디렉터리) / buffer pool 128MB
- 앱: commerce-api local 프로필 + 명령행 인자 `--spring.profiles.active=local --spring.jpa.hibernate.ddl-auto=none --spring.jpa.show-sql=false --datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3306/loopers_perf --datasource.mysql-jpa.main.username=root --datasource.mysql-jpa.main.password=*** --server.port=8080 `
  개선 후(after) 측정도 같은 계정(root)·옵션으로 실행해야 비교할 수 있다.
- 데이터 보존 확인: loopers_perf.products 시작 전 10000000 / 종료 후 10000000
- 방식: curl 순차 1개, X-USER-ID: 1. 워밍업 후 측정 요청의 time_total 백분위(ms, nearest-rank).
  첫 요청이 99999999ms를 넘으면 워밍업 1회 + 측정 3회로 줄인다. 표의 '표본'이 요청별 실제 측정 수다.
- 요청당 SQL: 측정 직전 events_statements_summary_by_digest를 비우고, 측정 후 SCHEMA_NAME=loopers_perf(앱 커넥션) 실행 수를 요청 수로 나눈 값
- 응답 스냅샷: `docs/week4/perf-results/cache-hit-10m/api-*.tsv` (마지막 응답의 id·price·likeCount)
- 캐시 모드: hit (워밍업이 채운 캐시를 읽음)
- Redis 상태: 시작 전 version=1320 keys=0 / 종료 후 version=1320 keys=3 (목록 키 수는 SCAN으로 셈)
- 요청당 Redis 명령: 측정 구간 전후 INFO commandstats 차이 ÷ 표본 (도구가 보낸 INCR·INFO·SCAN 제외)
- 데이터: users 500000 / brands 200 (삭제 5) / products 10000000 (미삭제 9293588) / product_likes 9969594
- 테이블 크기(데이터+인덱스): brands 0MB(인덱스 0MB) / product_likes 957MB(인덱스 522MB) / products 2504MB(인덱스 1694MB) / users 15MB(인덱스 0MB)
- 인덱스: brands.PRIMARY(id), product_likes.idx_product_likes_product(product_id), product_likes.PRIMARY(id), product_likes.uk_product_likes_user_product(user_id,product_id), products.idx_products_brand_created(brand_id,created_at), products.idx_products_brand_likes(brand_id,like_count,id), products.idx_products_brand_price(brand_id,price,id), products.idx_products_created(created_at,id), products.idx_products_likes(like_count,id), products.idx_products_price(price,id), products.PRIMARY(id), users.PRIMARY(id)

| 요청 | 설명 | 경로 | 워밍업 | 표본 | p50 | p95 | p99 | 최대 | 200 응답 | 요청당 SQL | 요청당 SELECT | 요청당 SQL 시간(ms) | 요청당 목록 검색 SELECT | 요청당 Redis 명령 |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| R1 | 브랜드 1 가격순 1페이지 (과제 측정 대상) | `/api/v1/products?brandId=1&sort=price_asc&page=0&size=20` | 3 | 30 | 27.7 | 40.6 | 41.7 | 41.7 | 30/30 | 12.0 | 2.0 | 1.6 | 0.0 | GET 2.0 / SET 0.0 / INCR 0.0 / 기타 0.0 |
| R2 | 브랜드 200 가격순 1페이지 | `/api/v1/products?brandId=200&sort=price_asc&page=0&size=20` | 3 | 30 | 21.5 | 31.4 | 31.9 | 31.9 | 30/30 | 12.0 | 2.0 | 1.3 | 0.0 | GET 2.0 / SET 0.0 / INCR 0.0 / 기타 0.0 |
| R3 | 브랜드 200 좋아요순 1페이지 | `/api/v1/products?brandId=200&sort=likes_desc&page=0&size=20` | 3 | 30 | 18.8 | 30.9 | 50.3 | 50.3 | 30/30 | 12.0 | 2.0 | 1.5 | 0.0 | GET 2.0 / SET 0.0 / INCR 0.0 / 기타 0.0 |
| R4 | 상품 1 상세 | `/api/v1/products/1` | 3 | 30 | 16.6 | 27.3 | 27.5 | 27.5 | 30/30 | 14.0 | 4.0 | 1.6 | 0.0 | GET 0.0 / SET 0.0 / INCR 0.0 / 기타 0.0 |

<details><summary>요청별 SQL digest</summary>

```text
-- R1 /api/v1/products?brandId=1&sort=price_asc&page=0&size=20 (요청 30회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
120	6.72	SET autocommit = ?
60	8.14	COMMIT
60	6.98	SET SESSION TRANSACTION READ ONLY
60	3.11	SET SESSION TRANSACTION READ WRITE
30	14.40	SELECT pje1_0 . id , pje1_0 . brand_id , pje1_0 . created_at , pje1_0 . deleted , pje1_0 . like_count , pje1_0 . name , pje1_0 . price , pje1_0 . stock FROM pro
30	7.96	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?

-- R2 /api/v1/products?brandId=200&sort=price_asc&page=0&size=20 (요청 30회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
120	7.98	SET autocommit = ?
60	4.17	COMMIT
60	4.12	SET SESSION TRANSACTION READ ONLY
60	2.31	SET SESSION TRANSACTION READ WRITE
30	12.04	SELECT pje1_0 . id , pje1_0 . brand_id , pje1_0 . created_at , pje1_0 . deleted , pje1_0 . like_count , pje1_0 . name , pje1_0 . price , pje1_0 . stock FROM pro
30	7.66	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?

-- R3 /api/v1/products?brandId=200&sort=likes_desc&page=0&size=20 (요청 30회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
120	7.42	SET autocommit = ?
60	6.71	SET SESSION TRANSACTION READ ONLY
60	4.34	COMMIT
60	2.75	SET SESSION TRANSACTION READ WRITE
30	14.22	SELECT pje1_0 . id , pje1_0 . brand_id , pje1_0 . created_at , pje1_0 . deleted , pje1_0 . like_count , pje1_0 . name , pje1_0 . price , pje1_0 . stock FROM pro
30	9.51	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?

-- R4 /api/v1/products/1 (요청 30회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
120	8.54	SET autocommit = ?
60	4.87	SET SESSION TRANSACTION READ ONLY
60	4.78	COMMIT
60	3.00	SET SESSION TRANSACTION READ WRITE
30	8.47	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?
30	7.02	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
30	6.57	SELECT pje1_0 . id , pje1_0 . brand_id , pje1_0 . created_at , pje1_0 . deleted , pje1_0 . like_count , pje1_0 . name , pje1_0 . price , pje1_0 . stock FROM pro
30	5.11	SELECT pje1_0 . id , pje1_0 . like_count FROM products pje1_0 WHERE pje1_0 . id IN (...)

```

</details>

## API 측정: cache-miss-10m (2026-10-16 00:25)

- 환경: Linux 6.18.44-fc-v114 (x86_64) / CPU Intel(R) Xeon(R) Processor @ 2.10GHz, 2코어 / 메모리 8GB / MySQL 8.0.46-0ubuntu0.24.04.4 / Java: openjdk version "21.0.12.1" 2026-08-18
- 디스크 여유: 24G (MySQL 데이터 디렉터리) / buffer pool 128MB
- 앱: commerce-api local 프로필 + 명령행 인자 `--spring.profiles.active=local --spring.jpa.hibernate.ddl-auto=none --spring.jpa.show-sql=false --datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3306/loopers_perf --datasource.mysql-jpa.main.username=root --datasource.mysql-jpa.main.password=*** --server.port=8080 `
  개선 후(after) 측정도 같은 계정(root)·옵션으로 실행해야 비교할 수 있다.
- 데이터 보존 확인: loopers_perf.products 시작 전 10000000 / 종료 후 10000000
- 방식: curl 순차 1개, X-USER-ID: 1. 워밍업 후 측정 요청의 time_total 백분위(ms, nearest-rank).
  첫 요청이 99999999ms를 넘으면 워밍업 1회 + 측정 3회로 줄인다. 표의 '표본'이 요청별 실제 측정 수다.
- 요청당 SQL: 측정 직전 events_statements_summary_by_digest를 비우고, 측정 후 SCHEMA_NAME=loopers_perf(앱 커넥션) 실행 수를 요청 수로 나눈 값
- 응답 스냅샷: `docs/week4/perf-results/cache-miss-10m/api-*.tsv` (마지막 응답의 id·price·likeCount)
- 캐시 모드: miss (측정·워밍업 요청마다 직전에 INCR products:list:version, 요청 시간 밖)
- Redis 상태: 시작 전 version=1320 keys=3 / 종료 후 version=1419 keys=102 (목록 키 수는 SCAN으로 셈)
- 요청당 Redis 명령: 측정 구간 전후 INFO commandstats 차이 ÷ 표본 (도구가 보낸 INCR·INFO·SCAN 제외)
- 데이터: users 500000 / brands 200 (삭제 5) / products 10000000 (미삭제 9293588) / product_likes 9969594
- 테이블 크기(데이터+인덱스): brands 0MB(인덱스 0MB) / product_likes 957MB(인덱스 522MB) / products 2504MB(인덱스 1694MB) / users 15MB(인덱스 0MB)
- 인덱스: brands.PRIMARY(id), product_likes.idx_product_likes_product(product_id), product_likes.PRIMARY(id), product_likes.uk_product_likes_user_product(user_id,product_id), products.idx_products_brand_created(brand_id,created_at), products.idx_products_brand_likes(brand_id,like_count,id), products.idx_products_brand_price(brand_id,price,id), products.idx_products_created(created_at,id), products.idx_products_likes(like_count,id), products.idx_products_price(price,id), products.PRIMARY(id), users.PRIMARY(id)

| 요청 | 설명 | 경로 | 워밍업 | 표본 | p50 | p95 | p99 | 최대 | 200 응답 | 요청당 SQL | 요청당 SELECT | 요청당 SQL 시간(ms) | 요청당 목록 검색 SELECT | 요청당 Redis 명령 |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| R1 | 브랜드 1 가격순 1페이지 (과제 측정 대상) | `/api/v1/products?brandId=1&sort=price_asc&page=0&size=20` | 3 | 30 | 34.4 | 58.7 | 64.8 | 64.8 | 30/30 | 15.0 | 5.0 | 3.1 | 1.0 | GET 2.0 / SET 1.0 / INCR 0.0 / 기타 0.1 |
| R2 | 브랜드 200 가격순 1페이지 | `/api/v1/products?brandId=200&sort=price_asc&page=0&size=20` | 3 | 30 | 28.7 | 38.5 | 43.8 | 43.8 | 30/30 | 15.0 | 5.0 | 3.3 | 1.0 | GET 2.0 / SET 1.0 / INCR 0.0 / 기타 0.1 |
| R3 | 브랜드 200 좋아요순 1페이지 | `/api/v1/products?brandId=200&sort=likes_desc&page=0&size=20` | 3 | 30 | 24.5 | 42.4 | 43.6 | 43.6 | 30/30 | 15.0 | 5.0 | 3.8 | 1.0 | GET 2.0 / SET 1.0 / INCR 0.0 / 기타 0.0 |

<details><summary>요청별 SQL digest</summary>

```text
-- R1 /api/v1/products?brandId=1&sort=price_asc&page=0&size=20 (요청 30회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
120	8.25	SET autocommit = ?
60	6.66	SET SESSION TRANSACTION READ ONLY
60	5.33	COMMIT
60	2.68	SET SESSION TRANSACTION READ WRITE
30	34.28	SELECT p . * FROM products p WHERE p . deleted = FALSE AND p . brand_id = ? ORDER BY p . price ASC , p . id DESC LIMIT ? OFFSET ?
30	11.33	SELECT pje1_0 . id , pje1_0 . brand_id , pje1_0 . created_at , pje1_0 . deleted , pje1_0 . like_count , pje1_0 . name , pje1_0 . price , pje1_0 . stock FROM pro
30	8.75	SELECT pje1_0 . id , pje1_0 . like_count FROM products pje1_0 WHERE pje1_0 . id IN (...)
30	8.65	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
30	7.25	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?

-- R2 /api/v1/products?brandId=200&sort=price_asc&page=0&size=20 (요청 30회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
120	7.01	SET autocommit = ?
60	4.97	SET SESSION TRANSACTION READ WRITE
60	4.40	SET SESSION TRANSACTION READ ONLY
60	4.18	COMMIT
30	35.07	SELECT p . * FROM products p WHERE p . deleted = FALSE AND p . brand_id = ? ORDER BY p . price ASC , p . id DESC LIMIT ? OFFSET ?
30	14.24	SELECT pje1_0 . id , pje1_0 . like_count FROM products pje1_0 WHERE pje1_0 . id IN (...)
30	11.93	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
30	9.95	SELECT pje1_0 . id , pje1_0 . brand_id , pje1_0 . created_at , pje1_0 . deleted , pje1_0 . like_count , pje1_0 . name , pje1_0 . price , pje1_0 . stock FROM pro
30	6.35	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?

-- R3 /api/v1/products?brandId=200&sort=likes_desc&page=0&size=20 (요청 30회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
120	8.49	SET autocommit = ?
60	4.62	SET SESSION TRANSACTION READ ONLY
60	4.12	COMMIT
60	3.28	SET SESSION TRANSACTION READ WRITE
30	54.36	SELECT p . * FROM products p WHERE p . deleted = FALSE AND p . brand_id = ? ORDER BY p . like_count DESC , p . id DESC LIMIT ? OFFSET ?
30	10.73	SELECT pje1_0 . id , pje1_0 . brand_id , pje1_0 . created_at , pje1_0 . deleted , pje1_0 . like_count , pje1_0 . name , pje1_0 . price , pje1_0 . stock FROM pro
30	10.65	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
30	9.62	SELECT pje1_0 . id , pje1_0 . like_count FROM products pje1_0 WHERE pje1_0 . id IN (...)
30	7.08	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?

```

</details>

-- product-perf-seed.sql 검증 쿼리
--
-- 실행: docker compose -f ./docker/infra-compose.yml exec -T mysql \
--         mysql -uapplication -papplication loopers --table < apps/commerce-api/src/test/resources/sql/product-perf-verify.sql
--
-- 모든 행의 ok 가 'OK' 이고 마지막 mismatch 가 0 이면 seed 가 공식대로 들어간 것이다.

-- 1. products 전체: count 10000, SUM(amount) 472,685,000
SELECT
    'products total'         AS item,
    10000                    AS expected_count,
    COUNT(*)                 AS actual_count,
    472685000                AS expected_sum,
    SUM(amount)              AS actual_sum,
    IF(COUNT(*) = 10000 AND SUM(amount) = 472685000, 'OK', 'MISMATCH') AS ok
FROM products;

-- 2. brand_id = 7: count 100, SUM(amount) 4,647,200
SELECT
    'products brand 7'       AS item,
    100                      AS expected_count,
    COUNT(*)                 AS actual_count,
    4647200                  AS expected_sum,
    SUM(amount)              AS actual_sum,
    IF(COUNT(*) = 100 AND SUM(amount) = 4647200, 'OK', 'MISMATCH') AS ok
FROM products
WHERE brand_id = 7;

-- 3. likes 전체: count 223,037
SELECT
    'likes total'            AS item,
    223037                   AS expected_count,
    COUNT(*)                 AS actual_count,
    IF(COUNT(*) = 223037, 'OK', 'MISMATCH') AS ok
FROM likes;

-- 4. 좋아요 0개 상품 909개, 500개 상품 10개
SELECT
    'products with like count 0 / 500' AS item,
    909                      AS expected_zero,
    SUM(c.like_count = 0)    AS actual_zero,
    10                       AS expected_500,
    SUM(c.like_count = 500)  AS actual_500,
    IF(SUM(c.like_count = 0) = 909 AND SUM(c.like_count = 500) = 10, 'OK', 'MISMATCH') AS ok
FROM (
    SELECT p.id, COUNT(l.id) AS like_count
    FROM products p
    LEFT JOIN likes l ON l.product_id = p.id
    GROUP BY p.id
) c;

-- 5. 상품별 좋아요 수가 공식과 다른 상품 수 (0건이어야 한다)
SELECT
    'products whose like count differs from formula' AS item,
    0                        AS expected_mismatch,
    COUNT(*)                 AS actual_mismatch,
    IF(COUNT(*) = 0, 'OK', 'MISMATCH') AS ok
FROM (
    SELECT
        p.id,
        COUNT(l.id) AS actual_count,
        CASE
            WHEN p.id % 999 = 0 THEN 500
            WHEN p.id % 11 = 0 THEN 0
            ELSE ((p.id * 13) % 47) + 1
        END AS expected_count
    FROM products p
    LEFT JOIN likes l ON l.product_id = p.id
    GROUP BY p.id
) c
WHERE c.actual_count <> c.expected_count;

-- 6. 좋아요 0개 상품이 있는 브랜드 수 = 100 (모든 브랜드에 좋아요 0개 상품이 섞여 있는지)
SELECT
    'brands having a zero-like product' AS item,
    100                      AS expected_count,
    COUNT(DISTINCT c.brand_id) AS actual_count,
    IF(COUNT(DISTINCT c.brand_id) = 100, 'OK', 'MISMATCH') AS ok
FROM (
    SELECT p.brand_id, COUNT(l.id) AS like_count
    FROM products p
    LEFT JOIN likes l ON l.product_id = p.id
    GROUP BY p.id, p.brand_id
) c
WHERE c.like_count = 0;

-- 7. 브랜드별 서로 다른 좋아요 수 종류의 최솟값 = 48 (브랜드 안에서 좋아요가 한 값에 몰리지 않는지)
SELECT
    'min distinct like counts per brand' AS item,
    48                       AS expected_min,
    MIN(d.distinct_counts)   AS actual_min,
    IF(MIN(d.distinct_counts) = 48, 'OK', 'MISMATCH') AS ok
FROM (
    SELECT c.brand_id, COUNT(DISTINCT c.like_count) AS distinct_counts
    FROM (
        SELECT p.brand_id, COUNT(l.id) AS like_count
        FROM products p
        LEFT JOIN likes l ON l.product_id = p.id
        GROUP BY p.id, p.brand_id
    ) c
    GROUP BY c.brand_id
) d;

-- 8. 브랜드 7의 서로 다른 좋아요 수 종류 = 48
SELECT
    'distinct like counts in brand 7' AS item,
    48                       AS expected_count,
    COUNT(DISTINCT c.like_count) AS actual_count,
    IF(COUNT(DISTINCT c.like_count) = 48, 'OK', 'MISMATCH') AS ok
FROM (
    SELECT p.id, COUNT(l.id) AS like_count
    FROM products p
    LEFT JOIN likes l ON l.product_id = p.id
    WHERE p.brand_id = 7
    GROUP BY p.id
) c;

-- 9. brands·users·products 기본 조건 (좋아요 공식과 무관하게 항상 같아야 한다)
SELECT 'brands count' AS item, 100 AS expected, COUNT(*) AS actual,
       IF(COUNT(*) = 100, 'OK', 'MISMATCH') AS ok
FROM brands
UNION ALL
SELECT 'brands name <> brand-{id}', 0, COUNT(*),
       IF(COUNT(*) = 0, 'OK', 'MISMATCH')
FROM brands WHERE name <> CONCAT('brand-', id)
UNION ALL
SELECT 'users count', 1000, COUNT(*),
       IF(COUNT(*) = 1000, 'OK', 'MISMATCH')
FROM users
UNION ALL
SELECT 'brand_id 1~100 with product count <> 100', 0, COUNT(*),
       IF(COUNT(*) = 0, 'OK', 'MISMATCH')
FROM (
    SELECT b.id, COUNT(p.id) AS c
    FROM brands b
    LEFT JOIN products p ON p.brand_id = b.id
    GROUP BY b.id
) t WHERE t.c <> 100
UNION ALL
SELECT 'products distinct created_at', 10000, COUNT(DISTINCT created_at),
       IF(COUNT(DISTINCT created_at) = 10000, 'OK', 'MISMATCH')
FROM products
UNION ALL
SELECT 'products created_at <> formula', 0, COUNT(*),
       IF(COUNT(*) = 0, 'OK', 'MISMATCH')
FROM products
WHERE created_at <> TIMESTAMP '2026-01-01 00:00:00' + INTERVAL ((id * 7919) % 10000) MINUTE
UNION ALL
SELECT 'products quantity <> 100', 0, COUNT(*),
       IF(COUNT(*) = 0, 'OK', 'MISMATCH')
FROM products WHERE quantity <> 100
UNION ALL
SELECT 'products name <> product-{id}', 0, COUNT(*),
       IF(COUNT(*) = 0, 'OK', 'MISMATCH')
FROM products WHERE name <> CONCAT('product-', id)
UNION ALL
SELECT 'brands deleted_at not null', 0, COUNT(*),
       IF(COUNT(*) = 0, 'OK', 'MISMATCH')
FROM brands WHERE deleted_at IS NOT NULL
UNION ALL
SELECT 'users deleted_at not null', 0, COUNT(*),
       IF(COUNT(*) = 0, 'OK', 'MISMATCH')
FROM users WHERE deleted_at IS NOT NULL
UNION ALL
SELECT 'products deleted_at not null', 0, COUNT(*),
       IF(COUNT(*) = 0, 'OK', 'MISMATCH')
FROM products WHERE deleted_at IS NOT NULL
UNION ALL
SELECT 'likes deleted_at not null', 0, COUNT(*),
       IF(COUNT(*) = 0, 'OK', 'MISMATCH')
FROM likes WHERE deleted_at IS NOT NULL;

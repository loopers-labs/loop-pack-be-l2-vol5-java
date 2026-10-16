-- 01-brands

EXPLAIN INSERT INTO brands (id, name, description, deleted, created_at, updated_at)
WITH digits AS (
    SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
), brand_numbers AS (
    SELECT 100 * hundreds.n + 10 * tens.n + ones.n + 1 AS b
    FROM digits hundreds CROSS JOIN digits tens CROSS JOIN digits ones
)
SELECT b, CONCAT('brand-', b), 'benchmark', b >= 991,
       '2026-10-01 00:00:00.000000', '2026-10-01 00:00:00.000000'
FROM brand_numbers;

-- 01-brands

EXPLAIN FORMAT=TREE INSERT INTO brands (id, name, description, deleted, created_at, updated_at)
WITH digits AS (
    SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
), brand_numbers AS (
    SELECT 100 * hundreds.n + 10 * tens.n + ones.n + 1 AS b
    FROM digits hundreds CROSS JOIN digits tens CROSS JOIN digits ones
)
SELECT b, CONCAT('brand-', b), 'benchmark', b >= 991,
       '2026-10-01 00:00:00.000000', '2026-10-01 00:00:00.000000'
FROM brand_numbers;

-- 02-users

EXPLAIN INSERT INTO users (id)
SELECT id FROM brands;

-- 02-users

EXPLAIN FORMAT=TREE INSERT INTO users (id)
SELECT id FROM brands;

-- 03-wallets

EXPLAIN INSERT INTO wallets (user_id, balance)
SELECT id, 0 FROM users;

-- 03-wallets

EXPLAIN FORMAT=TREE INSERT INTO wallets (user_id, balance)
SELECT id, 0 FROM users;

-- 04-products

EXPLAIN INSERT INTO products (id, brand_id, name, description, price, stock, like_count, deleted, created_at, updated_at)
WITH digits AS (
    SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
), product_numbers AS (
    SELECT 100 * hundreds.n + 10 * tens.n + ones.n AS t
    FROM digits hundreds CROSS JOIN digits tens CROSS JOIN digits ones
)
SELECT 1000 * t + brands.id, brands.id, CONCAT('product-', 1000 * t + brands.id), 'benchmark',
       1000 + 100 * MOD(37 * brands.id + 17 * FLOOR(t / 2), 1000),
       CASE WHEN MOD(brands.id + t, 10) = 0 THEN 0 ELSE 1 + MOD(7 * brands.id + 13 * t, 100) END,
       CASE WHEN MOD(13 * brands.id + 17 * t, 1000) = 0 THEN 100 ELSE MOD(7 * brands.id + 11 * t, 6) END,
       brands.id >= 991 OR MOD(t + brands.id, 25) = 0,
       TIMESTAMPADD(MINUTE, -FLOOR(t / 2), '2026-10-01 00:00:00.000000'),
       TIMESTAMPADD(MINUTE, -FLOOR(t / 2), '2026-10-01 00:00:00.000000')
FROM product_numbers CROSS JOIN brands
ORDER BY 1000 * t + brands.id;

-- 04-products

EXPLAIN FORMAT=TREE INSERT INTO products (id, brand_id, name, description, price, stock, like_count, deleted, created_at, updated_at)
WITH digits AS (
    SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
), product_numbers AS (
    SELECT 100 * hundreds.n + 10 * tens.n + ones.n AS t
    FROM digits hundreds CROSS JOIN digits tens CROSS JOIN digits ones
)
SELECT 1000 * t + brands.id, brands.id, CONCAT('product-', 1000 * t + brands.id), 'benchmark',
       1000 + 100 * MOD(37 * brands.id + 17 * FLOOR(t / 2), 1000),
       CASE WHEN MOD(brands.id + t, 10) = 0 THEN 0 ELSE 1 + MOD(7 * brands.id + 13 * t, 100) END,
       CASE WHEN MOD(13 * brands.id + 17 * t, 1000) = 0 THEN 100 ELSE MOD(7 * brands.id + 11 * t, 6) END,
       brands.id >= 991 OR MOD(t + brands.id, 25) = 0,
       TIMESTAMPADD(MINUTE, -FLOOR(t / 2), '2026-10-01 00:00:00.000000'),
       TIMESTAMPADD(MINUTE, -FLOOR(t / 2), '2026-10-01 00:00:00.000000')
FROM product_numbers CROSS JOIN brands
ORDER BY 1000 * t + brands.id;

-- 05-product-likes-1-to-5

EXPLAIN INSERT INTO product_likes (id, user_id, product_id, created_at)
WITH like_numbers AS (
    SELECT 1 AS k UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
)
SELECT (products.id - 1) * 100 + k,
       1 + MOD(31 * products.brand_id + 17 * FLOOR((products.id - 1) / 1000) + k - 1, 1000),
       products.id, '2026-10-01 00:00:00.000000'
FROM products JOIN like_numbers ON k <= products.like_count
ORDER BY (products.id - 1) * 100 + k;

-- 05-product-likes-1-to-5

EXPLAIN FORMAT=TREE INSERT INTO product_likes (id, user_id, product_id, created_at)
WITH like_numbers AS (
    SELECT 1 AS k UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
)
SELECT (products.id - 1) * 100 + k,
       1 + MOD(31 * products.brand_id + 17 * FLOOR((products.id - 1) / 1000) + k - 1, 1000),
       products.id, '2026-10-01 00:00:00.000000'
FROM products JOIN like_numbers ON k <= products.like_count
ORDER BY (products.id - 1) * 100 + k;

-- 06-product-likes-6-to-100

EXPLAIN INSERT INTO product_likes (id, user_id, product_id, created_at)
WITH digits AS (
    SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
), like_numbers AS (
    SELECT 10 * tens.n + ones.n + 1 AS k
    FROM digits tens CROSS JOIN digits ones
)
SELECT (products.id - 1) * 100 + k,
       1 + MOD(31 * products.brand_id + 17 * FLOOR((products.id - 1) / 1000) + k - 1, 1000),
       products.id, '2026-10-01 00:00:00.000000'
FROM products CROSS JOIN like_numbers
WHERE products.like_count = 100 AND k >= 6
ORDER BY (products.id - 1) * 100 + k;

-- 06-product-likes-6-to-100

EXPLAIN FORMAT=TREE INSERT INTO product_likes (id, user_id, product_id, created_at)
WITH digits AS (
    SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
), like_numbers AS (
    SELECT 10 * tens.n + ones.n + 1 AS k
    FROM digits tens CROSS JOIN digits ones
)
SELECT (products.id - 1) * 100 + k,
       1 + MOD(31 * products.brand_id + 17 * FLOOR((products.id - 1) / 1000) + k - 1, 1000),
       products.id, '2026-10-01 00:00:00.000000'
FROM products CROSS JOIN like_numbers
WHERE products.like_count = 100 AND k >= 6
ORDER BY (products.id - 1) * 100 + k;

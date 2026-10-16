-- Read-only fixture verification. Connect to week4_r01_index_benchmark before execution.
-- All timestamps are UTC. Aggregate expectations come from the deterministic seed formulas.

SELECT DATABASE() AS database_name, VERSION() AS mysql_version,
       @@session.time_zone AS session_time_zone, @@global.time_zone AS global_time_zone;

-- Expected: brands=1000, deleted_brands=10, users=1000, wallets=1000, total_balance=0.
SELECT (SELECT COUNT(*) FROM brands) AS brands,
       (SELECT COUNT(*) FROM brands WHERE deleted = TRUE) AS deleted_brands,
       (SELECT COUNT(*) FROM users) AS users,
       (SELECT COUNT(*) FROM wallets) AS wallets,
       (SELECT SUM(balance) FROM wallets) AS total_balance;

-- Expected: 1000000 / 950400 / 49600 / 50950000000 / 2597980 / 166338 / 1000 / 100000.
SELECT COUNT(*) AS products,
       SUM(deleted = FALSE) AS active_products,
       SUM(deleted = TRUE) AS deleted_products,
       SUM(price) AS price_sum,
       SUM(like_count) AS stored_like_count_sum,
       SUM(like_count = 0) AS zero_like_products,
       SUM(like_count = 100) AS popular_products,
       SUM(stock = 0) AS zero_stock_products,
       MIN(created_at) AS oldest_created_at,
       MAX(created_at) AS newest_created_at,
       COUNT(DISTINCT created_at) AS distinct_created_at
FROM products;

-- Expected: all 1000 brands have exactly 1000 products.
SELECT COUNT(*) AS brands_with_unexpected_product_count
FROM (
    SELECT brands.id
    FROM brands LEFT JOIN products ON products.brand_id = brands.id
    GROUP BY brands.id
    HAVING COUNT(products.id) <> 1000
) unexpected;

-- Expected: total=1000 / active=960 / active_price_sum=49004000.
SELECT COUNT(*) AS brand_7_products,
       SUM(deleted = FALSE) AS brand_7_active_products,
       SUM(CASE WHEN deleted = FALSE THEN price ELSE 0 END) AS brand_7_active_price_sum
FROM products
WHERE brand_id = 7;

-- Expected: 2597980 relations; every following defect count is 0.
SELECT COUNT(*) AS product_likes FROM product_likes;

SELECT COUNT(*) AS duplicate_like_pairs
FROM (
    SELECT product_id, user_id
    FROM product_likes
    GROUP BY product_id, user_id
    HAVING COUNT(*) > 1
) duplicate_pairs;

SELECT COUNT(*) AS orphan_product_likes
FROM product_likes
LEFT JOIN products ON products.id = product_likes.product_id
LEFT JOIN users ON users.id = product_likes.user_id
WHERE products.id IS NULL OR users.id IS NULL;

SELECT COUNT(*) AS orphan_products
FROM products LEFT JOIN brands ON brands.id = products.brand_id
WHERE brands.id IS NULL;

SELECT COUNT(*) AS orphan_wallets
FROM wallets LEFT JOIN users ON users.id = wallets.user_id
WHERE users.id IS NULL;

SELECT COUNT(*) AS active_products_of_deleted_brands
FROM products JOIN brands ON brands.id = products.brand_id
WHERE products.deleted = FALSE AND brands.deleted = TRUE;

SELECT COUNT(*) AS like_count_mismatches
FROM products
LEFT JOIN (
    SELECT product_id, COUNT(*) AS actual_like_count
    FROM product_likes
    GROUP BY product_id
) actual ON actual.product_id = products.id
WHERE products.like_count <> COALESCE(actual.actual_like_count, 0);

-- Verify the formula for every product instead of checking only totals.
SELECT COUNT(*) AS product_formula_mismatches
FROM products
WHERE brand_id <> MOD(id - 1, 1000) + 1
   OR name <> CONCAT('product-', id)
   OR description <> 'benchmark'
   OR price <> 1000 + 100 * MOD(37 * brand_id + 17 * FLOOR(FLOOR((id - 1) / 1000) / 2), 1000)
   OR stock <> CASE WHEN MOD(brand_id + FLOOR((id - 1) / 1000), 10) = 0
                   THEN 0 ELSE 1 + MOD(7 * brand_id + 13 * FLOOR((id - 1) / 1000), 100) END
   OR like_count <> CASE WHEN MOD(13 * brand_id + 17 * FLOOR((id - 1) / 1000), 1000) = 0
                        THEN 100 ELSE MOD(7 * brand_id + 11 * FLOOR((id - 1) / 1000), 6) END
   OR deleted <> (brand_id >= 991 OR MOD(FLOOR((id - 1) / 1000) + brand_id, 25) = 0)
   OR created_at <> TIMESTAMPADD(MINUTE, -FLOOR(FLOOR((id - 1) / 1000) / 2), '2026-10-01 00:00:00.000000')
   OR updated_at <> created_at;

-- Like ID encodes k in 1..100; validate the exact expected user and k range.
SELECT COUNT(*) AS like_formula_mismatches
FROM product_likes JOIN products ON products.id = product_likes.product_id
WHERE FLOOR((product_likes.id - 1) / 100) + 1 <> product_likes.product_id
   OR MOD(product_likes.id - 1, 100) + 1 > products.like_count
   OR product_likes.user_id <> 1 + MOD(31 * products.brand_id + 17 * FLOOR((products.id - 1) / 1000)
                                         + MOD(product_likes.id - 1, 100), 1000)
   OR product_likes.created_at <> '2026-10-01 00:00:00.000000';

-- Expected: PRIMARY plus exactly the four pre-existing product indexes; no new all-product sort index.
SHOW INDEX FROM products;

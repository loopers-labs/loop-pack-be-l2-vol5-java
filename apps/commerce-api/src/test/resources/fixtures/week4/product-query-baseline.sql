-- W4 product-query baseline seed v1.
-- Apply only to an empty, isolated test database after the existing manual schema.
-- The application fixture users (1: alice, 2: bob, 3: admin) may already exist;
-- this script neither inserts nor changes them. Synthetic users are 10001..10200.
-- This is a one-shot fixture, not a migration or an idempotent data reset.
-- Recreate the owned test database for a repeat run. No domain data is deleted here.
--
-- Product ordinal i = 1..10000:
-- id = 100000 + i; brand_id = 1001 + ((i - 1) % 100).
-- price = 1000 + ((i * 37) % 1000) * 100 (intentional price ties).
-- stock = 0 when i % 17 = 0, otherwise 1 + (i % 50).
-- like count = 200 when i % 997 = 0, otherwise ((i - 1) DIV 100 + i) % 10.
-- Brand 1100 and all its products are deleted; other products are deleted when i % 113 = 0.
-- Existing likes on deleted products remain as historical relations.
-- Expected totals: brands 100, products 10000, synthetic users 200, likes 46955.
-- Visible products 9812; deleted products 188; zero-like products 999;
-- popular products (200 likes) 10; sold-out products 588; total price 509500000.
-- Fixed request: /api/v1/products?brandId=1007&sort=price_asc&page=0&size=20.
-- Its visible total is 99 (5 pages); their price sum is 5183100.

CREATE TEMPORARY TABLE w4_product_baseline_numbers (
    ordinal INT NOT NULL PRIMARY KEY
);

-- Four finite derived tables avoid recursive-CTE limits and the MySQL restriction
-- on reopening one temporary table multiple times within the same statement.
INSERT INTO w4_product_baseline_numbers (ordinal)
SELECT 1 + units.digit + tens.digit * 10 + hundreds.digit * 100 + thousands.digit * 1000
FROM (SELECT 0 AS digit UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) units
CROSS JOIN (SELECT 0 AS digit UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
            UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) tens
CROSS JOIN (SELECT 0 AS digit UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
            UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) hundreds
CROSS JOIN (SELECT 0 AS digit UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
            UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) thousands;

INSERT INTO brand (id, name, created_at, updated_at, deleted_at)
SELECT 1000 + ordinal,
       CONCAT('W4 brand ', LPAD(ordinal, 3, '0')),
       '2026-10-01 00:00:00',
       CASE WHEN ordinal = 100 THEN '2026-10-02 00:00:00' ELSE '2026-10-01 00:00:00' END,
       CASE WHEN ordinal = 100 THEN '2026-10-02 00:00:00' ELSE NULL END
FROM w4_product_baseline_numbers
WHERE ordinal <= 100;

INSERT INTO `user` (id, point_balance, created_at, updated_at)
SELECT 10000 + ordinal, 100000000, '2026-10-01 00:00:00', '2026-10-01 00:00:00'
FROM w4_product_baseline_numbers
WHERE ordinal <= 200;

INSERT INTO product (id, brand_id, name, price, stock_quantity, created_at, updated_at, deleted_at)
SELECT 100000 + ordinal,
       1001 + MOD(ordinal - 1, 100),
       CONCAT('W4 product ', LPAD(ordinal, 5, '0')),
       1000 + MOD(ordinal * 37, 1000) * 100,
       CASE WHEN MOD(ordinal, 17) = 0 THEN 0 ELSE 1 + MOD(ordinal, 50) END,
       TIMESTAMPADD(SECOND, MOD(ordinal, 1000), '2026-10-01 00:00:00'),
       CASE WHEN MOD(ordinal - 1, 100) = 99 OR MOD(ordinal, 113) = 0
            THEN '2026-10-02 00:00:00'
            ELSE TIMESTAMPADD(SECOND, MOD(ordinal, 1000), '2026-10-01 00:00:00') END,
       CASE WHEN MOD(ordinal - 1, 100) = 99 OR MOD(ordinal, 113) = 0
            THEN '2026-10-02 00:00:00' ELSE NULL END
FROM w4_product_baseline_numbers;

INSERT INTO `like` (id, user_id, product_id, created_at)
SELECT (product.id - 100001) * 200 + numbers.ordinal,
       10000 + numbers.ordinal,
       product.id,
       TIMESTAMPADD(SECOND, numbers.ordinal, '2026-10-01 12:00:00')
FROM product
JOIN w4_product_baseline_numbers numbers
  ON numbers.ordinal <= CASE WHEN MOD(product.id - 100000, 997) = 0 THEN 200
                             ELSE MOD(((product.id - 100001) DIV 100) + product.id - 100000, 10) END
WHERE product.id BETWEEN 100001 AND 110000
  AND numbers.ordinal <= 200;

DROP TEMPORARY TABLE w4_product_baseline_numbers;

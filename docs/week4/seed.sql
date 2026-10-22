-- ============================================================
-- 성능 측정용 시드 데이터
-- 공식: brand_id=(i%100)+1 / price=((i*37)%100000)+100
-- 검증: 전체 count=10,000 / price 합=472,685,000
--       brand_id=7: count=100 / price 합=4,647,200
-- ============================================================

SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE likes;
TRUNCATE TABLE order_items;
TRUNCATE TABLE orders;
TRUNCATE TABLE points;
TRUNCATE TABLE products;
TRUNCATE TABLE brands;
TRUNCATE TABLE users;
SET FOREIGN_KEY_CHECKS = 1;

-- ── 1. 브랜드 100개 ──────────────────────────────────────────
INSERT INTO brands (name, created_at, updated_at, deleted_at)
WITH d AS (
    SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
),
nums AS (
    SELECT a.n + b.n*10 + 1 AS i FROM d a, d b
    WHERE a.n + b.n*10 + 1 <= 100
)
SELECT CONCAT('브랜드_', LPAD(i, 3, '0')), NOW(), NOW(), NULL
FROM nums ORDER BY i;

-- ── 2. 유저 200명 ────────────────────────────────────────────
INSERT INTO users (name, created_at, updated_at, deleted_at)
WITH d AS (
    SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
),
nums AS (
    SELECT a.n + b.n*10 + c.n*100 + 1 AS i FROM d a, d b, d c
    WHERE a.n + b.n*10 + c.n*100 + 1 <= 200
)
SELECT CONCAT('user_', LPAD(i, 3, '0')), NOW(), NOW(), NULL
FROM nums ORDER BY i;

-- ── 3. 상품 10,000개 ─────────────────────────────────────────
INSERT INTO products (brand_id, name, price, stock, created_at, updated_at, deleted_at)
WITH d AS (
    SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
),
nums AS (
    SELECT a.n + b.n*10 + c.n*100 + d2.n*1000 + 1 AS i
    FROM d a, d b, d c, d d2
    WHERE a.n + b.n*10 + c.n*100 + d2.n*1000 + 1 <= 10000
)
SELECT
    (i % 100) + 1            AS brand_id,
    CONCAT('상품_', LPAD(i, 5, '0')) AS name,
    ((i * 37) % 100000) + 100 AS price,
    (i * 13) % 101            AS stock,
    NOW(), NOW(), NULL
FROM nums ORDER BY i;

-- ── 4. 좋아요 — 인기 상품 (i%200=1, 50개, 각 100개 좋아요) ──
-- user_id 1..100 × product_id = product_seq(i%200=1)
INSERT IGNORE INTO likes (user_id, product_id, created_at, updated_at, deleted_at)
WITH d AS (
    SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
),
all_nums AS (
    SELECT a.n + b.n*10 + c.n*100 + d2.n*1000 + 1 AS i
    FROM d a, d b, d c, d d2
    WHERE a.n + b.n*10 + c.n*100 + d2.n*1000 + 1 <= 10000
),
products_tier1 AS (SELECT i FROM all_nums WHERE i % 200 = 1),
user_ranks     AS (SELECT i FROM all_nums WHERE i <= 100)
SELECT u.i AS user_id, p.i AS product_id, NOW(), NOW(), NULL
FROM products_tier1 p CROSS JOIN user_ranks u;

-- ── 5. 좋아요 — 중간 상품 (i%50=1 AND i%200!=1, 150개, 각 20개) ──
-- user_id 101..120
INSERT IGNORE INTO likes (user_id, product_id, created_at, updated_at, deleted_at)
WITH d AS (
    SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
),
all_nums AS (
    SELECT a.n + b.n*10 + c.n*100 + d2.n*1000 + 1 AS i
    FROM d a, d b, d c, d d2
    WHERE a.n + b.n*10 + c.n*100 + d2.n*1000 + 1 <= 10000
),
products_tier2 AS (SELECT i FROM all_nums WHERE i % 50 = 1 AND i % 200 != 1),
user_ranks     AS (SELECT i FROM all_nums WHERE i <= 20)
SELECT u.i + 100 AS user_id, p.i AS product_id, NOW(), NOW(), NULL
FROM products_tier2 p CROSS JOIN user_ranks u;

-- ── 6. 좋아요 — 소량 상품 (i%10=1 AND i%50!=1, 700개, 각 5개) ──
-- user_id 151..155
INSERT IGNORE INTO likes (user_id, product_id, created_at, updated_at, deleted_at)
WITH d AS (
    SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
),
all_nums AS (
    SELECT a.n + b.n*10 + c.n*100 + d2.n*1000 + 1 AS i
    FROM d a, d b, d c, d d2
    WHERE a.n + b.n*10 + c.n*100 + d2.n*1000 + 1 <= 10000
),
products_tier3 AS (SELECT i FROM all_nums WHERE i % 10 = 1 AND i % 50 != 1),
user_ranks     AS (SELECT i FROM all_nums WHERE i <= 5)
SELECT u.i + 150 AS user_id, p.i AS product_id, NOW(), NOW(), NULL
FROM products_tier3 p CROSS JOIN user_ranks u;

-- ── 검증 쿼리 ────────────────────────────────────────────────
SELECT 'all_products'   AS label, COUNT(*) AS cnt, SUM(price) AS price_sum FROM products WHERE deleted_at IS NULL
UNION ALL
SELECT 'brand_id_7',    COUNT(*), SUM(price) FROM products WHERE deleted_at IS NULL AND brand_id = 7
UNION ALL
SELECT 'users',         COUNT(*), NULL FROM users
UNION ALL
SELECT 'likes',         COUNT(*), NULL FROM likes;

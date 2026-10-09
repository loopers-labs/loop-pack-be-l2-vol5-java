-- r04_before 에 브랜드 1,000개 / 상품 1,000,000건(삭제 5%) / 편중된 좋아요 수를 채운다. (before.sql 실행 후)
-- 좋아요 수: 1% 상품은 1만~6만, 나머지는 약 1/3이 집계 행 없음(0), 그 외 1~10.
USE r04_before;
SET SESSION cte_max_recursion_depth = 1000000;

INSERT INTO brands (id, name, description, deleted, created_at, updated_at)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 1000)
SELECT n, CONCAT('brand-', n), 'seed', 0, NOW(6), NOW(6) FROM seq;

INSERT INTO products (id, brand_id, name, description, price, stock, deleted, created_at, updated_at)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 1000000)
SELECT n,
       1 + (n * 7919) % 1000,
       CONCAT('product-', n),
       'seed',
       1000 + (n * 37) % 100000,
       100,
       IF(n % 20 = 0, 1, 0),
       TIMESTAMP(NOW(6)) - INTERVAL n SECOND,
       NOW(6)
FROM seq;

INSERT INTO product_like_counts (product_id, like_count)
SELECT id,
       CASE WHEN id % 100 = 1 THEN 10000 + (id * 13) % 50000
            ELSE id % 11 END
FROM products
WHERE id % 100 = 1 OR (id % 3 <> 0 AND id % 11 <> 0);

ANALYZE TABLE brands, products, product_like_counts;

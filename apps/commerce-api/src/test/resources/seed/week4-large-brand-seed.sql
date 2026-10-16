-- 4주차 브랜드 + 좋아요순 측정용 추가 seed (docs/week4/product-query-seed.md 4절)
-- week4-product-seed.sql 을 넣고 기존 장면을 잰 뒤에 넣는다. 상품 수가 많은 브랜드 하나를 더한다.
-- i = 상품 순번이자 식별자 (10,001..30,000), 브랜드는 모두 101. 가격 · 생성 시각 · 삭제 · 재고 · 좋아요 규칙은 기본 seed 와 같다

SET SESSION cte_max_recursion_depth = 20000;

INSERT INTO brand (id, name, description, created_at, updated_at, deleted_at)
VALUES (101, 'brand-101', 'brand-101 description', TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00', NULL);

INSERT INTO product (id, brand_id, name, price, stock, created_at, updated_at, deleted_at)
WITH RECURSIVE seq (i) AS (SELECT 10001 UNION ALL SELECT i + 1 FROM seq WHERE i < 30000)
SELECT i,
       101,
       CONCAT('product-', i),
       ((i * 37) % 100000) + 100,
       CASE WHEN i % 13 = 0 THEN 0 ELSE 10 END,
       TIMESTAMPADD(SECOND, (i * 7919) % 10000, TIMESTAMP '2026-01-01 00:00:00'),
       CASE WHEN i % 17 = 0 THEN TIMESTAMP '2026-02-01 00:00:00'
            ELSE TIMESTAMPADD(SECOND, (i * 7919) % 10000, TIMESTAMP '2026-01-01 00:00:00') END,
       CASE WHEN i % 17 = 0 THEN TIMESTAMP '2026-02-01 00:00:00' END
FROM seq;

INSERT INTO likes (user_id, product_id, created_at, updated_at, deleted_at)
WITH RECURSIVE
    product_seq (i) AS (SELECT 10001 UNION ALL SELECT i + 1 FROM product_seq WHERE i < 30000),
    like_seq (j) AS (SELECT 0 UNION ALL SELECT j + 1 FROM like_seq WHERE j < 249)
SELECT ((p.i * 7 + l.j) % 1000) + 1,
       p.i,
       TIMESTAMPADD(SECOND, l.j, TIMESTAMP '2026-01-15 00:00:00'),
       TIMESTAMPADD(SECOND, l.j, TIMESTAMP '2026-01-15 00:00:00'),
       NULL
FROM product_seq p
JOIN like_seq l ON l.j < CASE
    WHEN p.i % 97 = 0 THEN 250
    WHEN p.i % 7 = 0 THEN 0
    ELSE ((p.i * 31) % 97) % 17 + 1
END;

INSERT INTO product_like_count (product_id, like_count, created_at, updated_at, deleted_at)
SELECT p.id, COUNT(l.id), p.created_at, p.created_at, NULL
FROM product p
LEFT JOIN likes l ON l.product_id = p.id
WHERE p.brand_id = 101
GROUP BY p.id, p.created_at;

SET SESSION cte_max_recursion_depth = 1000;

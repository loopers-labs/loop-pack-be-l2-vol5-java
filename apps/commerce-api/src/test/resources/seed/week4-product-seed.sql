-- 4주차 상품 조회 측정용 seed (4주차 설계 docs/week4/product-query-seed.md)
-- 빈 스키마(ddl-auto: create 직후)에 넣는다. 같은 규칙으로 언제든 같은 데이터를 다시 만든다.
-- i = 상품 순번이자 식별자 (1..10,000)

SET SESSION cte_max_recursion_depth = 10000;

-- 사용자 1,000명. 좋아요 관계의 user_id 가 실제 행을 가리키게 한다
INSERT INTO users (id, created_at, updated_at, deleted_at)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 1000)
SELECT n, TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00', NULL
FROM seq;

-- 브랜드 100개. 모두 살아 있다
INSERT INTO brand (id, name, description, created_at, updated_at, deleted_at)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 100)
SELECT n, CONCAT('brand-', n), CONCAT('brand-', n, ' description'),
       TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00', NULL
FROM seq;

-- 상품 10,000개
--   브랜드     (i % 100) + 1
--   가격       ((i * 37) % 100000) + 100   (이 범위에서는 가격 동점이 없음)
--   생성 시각  기준 + ((i * 7919) % 10000)초   (식별자 순서와 다른 순열, 중복 없음)
--   삭제       i % 17 = 0 → 588개, 모든 브랜드에 퍼짐
--   재고 0     i % 13 = 0
INSERT INTO product (id, brand_id, name, price, stock, created_at, updated_at, deleted_at)
WITH RECURSIVE seq (i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM seq WHERE i < 10000)
SELECT i,
       (i % 100) + 1,
       CONCAT('product-', i),
       ((i * 37) % 100000) + 100,
       CASE WHEN i % 13 = 0 THEN 0 ELSE 10 END,
       TIMESTAMPADD(SECOND, (i * 7919) % 10000, TIMESTAMP '2026-01-01 00:00:00'),
       CASE WHEN i % 17 = 0 THEN TIMESTAMP '2026-02-01 00:00:00'
            ELSE TIMESTAMPADD(SECOND, (i * 7919) % 10000, TIMESTAMP '2026-01-01 00:00:00') END,
       CASE WHEN i % 17 = 0 THEN TIMESTAMP '2026-02-01 00:00:00' END
FROM seq;

-- 좋아요 100,163건. 상품 i 의 좋아요 수 L(i)
--   i % 97 = 0 → 250 (인기)
--   i % 7  = 0 → 0
--   나머지     → ((i * 31) % 97) % 17 + 1   (1..17, 동점 다수)
-- 상품 i 의 j 번째 (0 ≤ j < L(i)) 좋아요 사용자 = ((i * 7 + j) % 1000) + 1  (같은 상품 안에서 중복 없음)
-- 삭제된 상품의 관계도 남긴다 (2주차 설계 6.3)
INSERT INTO likes (user_id, product_id, created_at, updated_at, deleted_at)
WITH RECURSIVE
    product_seq (i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM product_seq WHERE i < 10000),
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

-- 상품별 좋아요 수 행. 모든 상품이 한 행을 가지며 값은 관계 수와 같다 (4주차 설계 docs/week4/like-count-design.md 2.2)
-- 운영 데이터의 backfill 도 같은 문장이다
INSERT INTO product_like_count (product_id, like_count, created_at, updated_at, deleted_at)
SELECT p.id, COUNT(l.id), p.created_at, p.created_at, NULL
FROM product p
LEFT JOIN likes l ON l.product_id = p.id
GROUP BY p.id, p.created_at;

SET SESSION cte_max_recursion_depth = 1000;

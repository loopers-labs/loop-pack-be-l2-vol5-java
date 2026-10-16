-- 4주차 옵티마이저 계획 실험용 추가 seed (docs/week4/product-query-seed.md 5절)
-- week4-product-seed.sql 과 week4-large-brand-seed.sql 뒤에 넣는다.
-- 브랜드 102: 상품 3,000개로 적지 않지만 모든 상품의 좋아요가 0 · 1개라, 좋아요 순서의 맨 뒤에 몰려 있다.
-- 필터(브랜드)와 정렬 키(좋아요 수)가 연관된 분포로, 옵티마이저의 균등 분포 가정이 맞지 않는 경우를 만든다.
-- i = 상품 순번이자 식별자 (30,001..33,000). 가격 · 생성 시각 · 삭제 · 재고 규칙은 기본 seed 와 같다. 좋아요 수 = i % 2

SET SESSION cte_max_recursion_depth = 3000;

INSERT INTO brand (id, name, description, created_at, updated_at, deleted_at)
VALUES (102, 'brand-102', 'brand-102 description', TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00', NULL);

INSERT INTO product (id, brand_id, name, price, stock, created_at, updated_at, deleted_at)
WITH RECURSIVE seq (i) AS (SELECT 30001 UNION ALL SELECT i + 1 FROM seq WHERE i < 33000)
SELECT i,
       102,
       CONCAT('product-', i),
       ((i * 37) % 100000) + 100,
       CASE WHEN i % 13 = 0 THEN 0 ELSE 10 END,
       TIMESTAMPADD(SECOND, (i * 7919) % 10000, TIMESTAMP '2026-01-01 00:00:00'),
       CASE WHEN i % 17 = 0 THEN TIMESTAMP '2026-02-01 00:00:00'
            ELSE TIMESTAMPADD(SECOND, (i * 7919) % 10000, TIMESTAMP '2026-01-01 00:00:00') END,
       CASE WHEN i % 17 = 0 THEN TIMESTAMP '2026-02-01 00:00:00' END
FROM seq;

INSERT INTO likes (user_id, product_id, created_at, updated_at, deleted_at)
WITH RECURSIVE seq (i) AS (SELECT 30001 UNION ALL SELECT i + 1 FROM seq WHERE i < 33000)
SELECT ((i * 7) % 1000) + 1, i, TIMESTAMP '2026-01-15 00:00:00', TIMESTAMP '2026-01-15 00:00:00', NULL
FROM seq
WHERE i % 2 = 1;

INSERT INTO product_like_count (product_id, like_count, created_at, updated_at, deleted_at)
SELECT p.id, COUNT(l.id), p.created_at, p.created_at, NULL
FROM product p
LEFT JOIN likes l ON l.product_id = p.id
WHERE p.brand_id = 102
GROUP BY p.id, p.created_at;

SET SESSION cte_max_recursion_depth = 1000;

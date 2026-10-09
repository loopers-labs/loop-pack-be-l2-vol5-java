-- 변경 전 좋아요순·COUNT 쿼리(현재 QueryDslProductQueryDao 와 동일한 형태). r04_before 에서 실행.
-- 실행: docker exec -i <mysql> mysql -uroot -proot -D r04_before < explain_before.sql
-- 1) 좋아요순, 필터 없음
EXPLAIN ANALYZE
SELECT p.id, p.name, p.price, b.id, b.name, COALESCE(c.like_count, 0)
FROM products p JOIN brands b ON b.id = p.brand_id
LEFT JOIN product_like_counts c ON c.product_id = p.id
WHERE p.deleted = 0 AND b.deleted = 0
ORDER BY COALESCE(c.like_count, 0) DESC, p.id DESC LIMIT 20 OFFSET 0;

EXPLAIN
SELECT p.id, p.name, p.price, b.id, b.name, COALESCE(c.like_count, 0)
FROM products p JOIN brands b ON b.id = p.brand_id
LEFT JOIN product_like_counts c ON c.product_id = p.id
WHERE p.deleted = 0 AND b.deleted = 0
ORDER BY COALESCE(c.like_count, 0) DESC, p.id DESC LIMIT 20 OFFSET 0;

-- 2) 좋아요순, 브랜드 필터
EXPLAIN ANALYZE
SELECT p.id, p.name, p.price, b.id, b.name, COALESCE(c.like_count, 0)
FROM products p JOIN brands b ON b.id = p.brand_id
LEFT JOIN product_like_counts c ON c.product_id = p.id
WHERE p.deleted = 0 AND b.deleted = 0 AND p.brand_id = 500
ORDER BY COALESCE(c.like_count, 0) DESC, p.id DESC LIMIT 20 OFFSET 0;

EXPLAIN
SELECT p.id, p.name, p.price, b.id, b.name, COALESCE(c.like_count, 0)
FROM products p JOIN brands b ON b.id = p.brand_id
LEFT JOIN product_like_counts c ON c.product_id = p.id
WHERE p.deleted = 0 AND b.deleted = 0 AND p.brand_id = 500
ORDER BY COALESCE(c.like_count, 0) DESC, p.id DESC LIMIT 20 OFFSET 0;

-- 3) COUNT, 필터 없음 (브랜드 조인 포함)
EXPLAIN ANALYZE
SELECT COUNT(*) FROM products p JOIN brands b ON b.id = p.brand_id WHERE p.deleted = 0 AND b.deleted = 0;

EXPLAIN
SELECT COUNT(*) FROM products p JOIN brands b ON b.id = p.brand_id WHERE p.deleted = 0 AND b.deleted = 0;

-- 4) COUNT, 브랜드 필터 (브랜드 조인 포함)
EXPLAIN ANALYZE
SELECT COUNT(*) FROM products p JOIN brands b ON b.id = p.brand_id
WHERE p.deleted = 0 AND b.deleted = 0 AND p.brand_id = 500;

EXPLAIN
SELECT COUNT(*) FROM products p JOIN brands b ON b.id = p.brand_id
WHERE p.deleted = 0 AND b.deleted = 0 AND p.brand_id = 500;

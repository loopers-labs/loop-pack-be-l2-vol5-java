-- 변경 후 좋아요순·COUNT 쿼리(변경 후 QueryDslProductQueryDao 와 동일한 형태). r04_after 에서 실행.
-- 실행: docker exec -i <mysql> mysql -uroot -proot -D r04_after < explain_after.sql
-- 1) 좋아요순, 필터 없음
EXPLAIN ANALYZE
SELECT p.id, p.name, p.price, b.id, b.name, p.like_count
FROM products p JOIN brands b ON b.id = p.brand_id
WHERE p.deleted = 0 AND b.deleted = 0
ORDER BY p.like_count DESC, p.id DESC LIMIT 20 OFFSET 0;

EXPLAIN
SELECT p.id, p.name, p.price, b.id, b.name, p.like_count
FROM products p JOIN brands b ON b.id = p.brand_id
WHERE p.deleted = 0 AND b.deleted = 0
ORDER BY p.like_count DESC, p.id DESC LIMIT 20 OFFSET 0;

-- 2) 좋아요순, 브랜드 필터
EXPLAIN ANALYZE
SELECT p.id, p.name, p.price, b.id, b.name, p.like_count
FROM products p JOIN brands b ON b.id = p.brand_id
WHERE p.deleted = 0 AND b.deleted = 0 AND p.brand_id = 500
ORDER BY p.like_count DESC, p.id DESC LIMIT 20 OFFSET 0;

EXPLAIN
SELECT p.id, p.name, p.price, b.id, b.name, p.like_count
FROM products p JOIN brands b ON b.id = p.brand_id
WHERE p.deleted = 0 AND b.deleted = 0 AND p.brand_id = 500
ORDER BY p.like_count DESC, p.id DESC LIMIT 20 OFFSET 0;

-- 3) COUNT, 필터 없음 (products 만)
EXPLAIN ANALYZE
SELECT COUNT(*) FROM products p WHERE p.deleted = 0;

EXPLAIN
SELECT COUNT(*) FROM products p WHERE p.deleted = 0;

-- 4) COUNT, 브랜드 필터 (products 만)
EXPLAIN ANALYZE
SELECT COUNT(*) FROM products p WHERE p.deleted = 0 AND p.brand_id = 500;

EXPLAIN
SELECT COUNT(*) FROM products p WHERE p.deleted = 0 AND p.brand_id = 500;

-- 두 스키마 공통 쿼리(최신순·가격순, 필터 없음 첫 페이지). 결과만 기록한다.
-- 실행: docker exec -i <mysql> mysql -uroot -proot -D r04_before < explain.sql   (r04_after 도 동일)
-- 좋아요순·COUNT 쿼리는 형태가 달라 explain_before.sql / explain_after.sql 로 나눈다.
EXPLAIN ANALYZE
SELECT p.id, p.name, p.price, b.id, b.name
FROM products p JOIN brands b ON b.id = p.brand_id
WHERE p.deleted = 0 AND b.deleted = 0
ORDER BY p.created_at DESC, p.id DESC LIMIT 20 OFFSET 0;

EXPLAIN
SELECT p.id, p.name, p.price, b.id, b.name
FROM products p JOIN brands b ON b.id = p.brand_id
WHERE p.deleted = 0 AND b.deleted = 0
ORDER BY p.created_at DESC, p.id DESC LIMIT 20 OFFSET 0;

EXPLAIN ANALYZE
SELECT p.id, p.name, p.price, b.id, b.name
FROM products p JOIN brands b ON b.id = p.brand_id
WHERE p.deleted = 0 AND b.deleted = 0
ORDER BY p.price ASC, p.id DESC LIMIT 20 OFFSET 0;

EXPLAIN
SELECT p.id, p.name, p.price, b.id, b.name
FROM products p JOIN brands b ON b.id = p.brand_id
WHERE p.deleted = 0 AND b.deleted = 0
ORDER BY p.price ASC, p.id DESC LIMIT 20 OFFSET 0;

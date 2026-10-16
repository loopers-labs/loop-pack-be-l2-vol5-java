-- 08-index-prefix: 대상 loopers_benchmark. 기록: ../../docs/week4/implementation-log.md (6절).
-- 실행 안내·기존 메모: ../../docs/week4/implementation-log.md#sql-reading-guide
USE loopers_benchmark;

-- 브랜드 전체 조회: 기존 짧은 인덱스.
EXPLAIN ANALYZE
SELECT p.* FROM product p FORCE INDEX (idx_product_brand_deleted)
WHERE p.brand_id = 7 AND p.deleted_at IS NULL;

-- 브랜드 전체 조회: 긴 인덱스의 선두 열로 대체.
EXPLAIN ANALYZE
SELECT p.* FROM product p FORCE INDEX (idx_product_brand_price)
WHERE p.brand_id = 7 AND p.deleted_at IS NULL;

-- 전체 건수 COUNT: 기존 짧은 인덱스.
EXPLAIN ANALYZE
SELECT COUNT(*) FROM product p FORCE INDEX (idx_product_brand_deleted)
WHERE p.brand_id = 7 AND p.deleted_at IS NULL
  AND EXISTS (SELECT 1 FROM brand b WHERE b.id = p.brand_id AND b.deleted_at IS NULL);

-- 전체 건수 COUNT: 긴 인덱스로 대체.
EXPLAIN ANALYZE
SELECT COUNT(*) FROM product p FORCE INDEX (idx_product_brand_price)
WHERE p.brand_id = 7 AND p.deleted_at IS NULL
  AND EXISTS (SELECT 1 FROM brand b WHERE b.id = p.brand_id AND b.deleted_at IS NULL);

-- 삭제 대상 ID 정렬: 기존 인덱스의 Sort 여부.
EXPLAIN ANALYZE
SELECT p.id FROM product p FORCE INDEX (idx_product_brand_deleted)
WHERE p.brand_id = 7 AND p.deleted_at IS NULL ORDER BY p.id;

-- 삭제 대상 ID 정렬: 긴 인덱스로 바꾸면 Sort가 생기는지.
EXPLAIN ANALYZE
SELECT p.id FROM product p FORCE INDEX (idx_product_brand_price)
WHERE p.brand_id = 7 AND p.deleted_at IS NULL ORDER BY p.id;

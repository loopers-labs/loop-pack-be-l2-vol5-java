-- 10-brand-in: 대상 loopers_benchmark. 기록: ../../docs/week4/implementation-log.md (8.1절).
-- 실행 안내·기존 메모: ../../docs/week4/implementation-log.md#sql-reading-guide
USE loopers_benchmark;

-- 브랜드 7: 실제 읽은 행 → 추가 정렬 → 반환한 행 확인.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC LIMIT 20;

-- 브랜드 7·8: 합친 가격순 결과의 읽기량·추가 정렬 확인.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at FROM product
WHERE brand_id IN (7, 8) AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC LIMIT 20;

-- 05 강제: 같은 두 브랜드 조회의 읽기량·추가 정렬 비교.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product FORCE INDEX (idx_product_brand_price)
WHERE brand_id IN (7, 8) AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC LIMIT 20;

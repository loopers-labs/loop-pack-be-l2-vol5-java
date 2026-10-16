-- 18-100k-before-after: 대상 loopers_benchmark_100k. 기록: ../../docs/week4/implementation-log.md (13절).
-- 실행 안내·기존 메모: ../../docs/week4/implementation-log.md#sql-reading-guide
USE loopers_benchmark_100k;

-- 가격 인덱스 제외: 기존 브랜드 인덱스의 읽기·정렬.
EXPLAIN ANALYZE
SELECT id,brand_id,name,price,created_at
FROM product IGNORE INDEX (idx_product_brand_price)
WHERE brand_id=7 AND deleted_at IS NULL
ORDER BY price ASC,created_at DESC,id DESC LIMIT 20;

-- 가격 인덱스 허용: 같은 20개 조회의 읽기·정렬.
EXPLAIN ANALYZE
SELECT id,brand_id,name,price,created_at
FROM product
WHERE brand_id=7 AND deleted_at IS NULL
ORDER BY price ASC,created_at DESC,id DESC LIMIT 20;

-- 좋아요 인덱스 제외: 전체 스캔 여부.
EXPLAIN ANALYZE
SELECT product_id,COUNT(id)
FROM product_like IGNORE INDEX (idx_product_like_product)
WHERE product_id IN (
    97407,94407,91407,88407,85407,82407,79407,76407,73407,70407,
    67407,64407,61407,58407,55407,52407,49407,46407,43407,40407
)
GROUP BY product_id;

-- 좋아요 인덱스 허용: 같은 페이지의 범위 스캔.
EXPLAIN ANALYZE
SELECT product_id,COUNT(id)
FROM product_like
WHERE product_id IN (
    97407,94407,91407,88407,85407,82407,79407,76407,73407,70407,
    67407,64407,61407,58407,55407,52407,49407,46407,43407,40407
)
GROUP BY product_id;

-- 동일한 20개 상품에 좋아요가 쏠릴 때 읽기량.
EXPLAIN ANALYZE
SELECT product_id,COUNT(id)
FROM loopers_benchmark_100k_hotspot.product_like
WHERE product_id IN (
    97407,94407,91407,88407,85407,82407,79407,76407,73407,70407,
    67407,64407,61407,58407,55407,52407,49407,46407,43407,40407
)
GROUP BY product_id;


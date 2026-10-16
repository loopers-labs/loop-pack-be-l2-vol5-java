-- 13-like-product-index: 대상 loopers_benchmark. 기록: ../../docs/week4/implementation-log.md (9절).
-- 실행 안내·기존 메모: ../../docs/week4/implementation-log.md#sql-reading-guide
USE loopers_benchmark;

-- 페이지 상품의 좋아요: product_id 범위 스캔·커버링 확인.
EXPLAIN ANALYZE
SELECT product_id, COUNT(id)
FROM product_like
WHERE product_id IN (
    7407,4407,1407,9407,6407,3407,407,8407,5407,2407,
    7707,4707,1707,9707,6707,3707,707,8707,5707,2707
)
GROUP BY product_id;

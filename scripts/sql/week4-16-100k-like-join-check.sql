-- 16-100k-like-join-check: 대상 loopers_benchmark_100k. 기록: ../../docs/week4/implementation-log.md (12절).
-- 실행 안내·기존 메모: ../../docs/week4/implementation-log.md#sql-reading-guide
USE loopers_benchmark_100k;

-- 전체 좋아요 건수: 범위 스캔과 비교할 기준.
SELECT COUNT(*) FROM product_like;

-- 통계 갱신 전: 페이지 좋아요만 읽는지.
EXPLAIN ANALYZE
SELECT product_id, COUNT(id)
FROM product_like
WHERE product_id IN (
    97407,94407,91407,88407,85407,82407,79407,76407,73407,70407,
    67407,64407,61407,58407,55407,52407,49407,46407,43407,40407
)
GROUP BY product_id;

-- 좋아요 통계 갱신.
ANALYZE TABLE product_like;

-- 통계 갱신 후: 범위 스캔·읽기량 유지 여부.
EXPLAIN ANALYZE
SELECT product_id, COUNT(id)
FROM product_like
WHERE product_id IN (
    97407,94407,91407,88407,85407,82407,79407,76407,73407,70407,
    67407,64407,61407,58407,55407,52407,49407,46407,43407,40407
)
GROUP BY product_id;

-- 좋아요 집계 Handler 시작값.
SHOW SESSION STATUS LIKE 'Handler_read%';

-- 10만 건의 첫 페이지 좋아요 집계 실행.
SELECT product_id, COUNT(id)
FROM product_like
WHERE product_id IN (
    97407,94407,91407,88407,85407,82407,79407,76407,73407,70407,
    67407,64407,61407,58407,55407,52407,49407,46407,43407,40407
)
GROUP BY product_id;

-- 좋아요 집계 Handler 종료값.
SHOW SESSION STATUS LIKE 'Handler_read%';

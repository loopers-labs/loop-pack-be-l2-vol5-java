-- 17-100k-hotspot: 대상 loopers_benchmark_100k_hotspot. 기록: ../../docs/week4/implementation-log.md (12절).
-- 실행 안내·기존 메모: ../../docs/week4/implementation-log.md#sql-reading-guide
USE loopers_benchmark_100k_hotspot;

-- 핫스팟 1페이지: 인기 상품 포함 시 실제 집계량.
EXPLAIN ANALYZE
SELECT product_id, COUNT(id)
FROM product_like
WHERE product_id IN (
    97407,94407,91407,88407,85407,82407,79407,76407,73407,70407,
    67407,64407,61407,58407,55407,52407,49407,46407,43407,40407
)
GROUP BY product_id;

-- 일반 2페이지: 인기 상품 미포함 시 실제 집계량.
EXPLAIN ANALYZE
SELECT product_id, COUNT(id)
FROM product_like
WHERE product_id IN (
    37407,34407,31407,28407,25407,19407,16407,13407,10407,7407,
    4407,1407,99407,96407,93407,90407,87407,84407,81407,78407
)
GROUP BY product_id;

-- 같은 연결의 서버 실행 시간 기록 시작.
SET profiling = 1;

-- 핫스팟 1페이지 집계 시간 1회차.
SELECT product_id, COUNT(id) FROM product_like WHERE product_id IN (
    97407,94407,91407,88407,85407,82407,79407,76407,73407,70407,
    67407,64407,61407,58407,55407,52407,49407,46407,43407,40407
) GROUP BY product_id;

-- 일반 2페이지 집계 시간 1회차.
SELECT product_id, COUNT(id) FROM product_like WHERE product_id IN (
    37407,34407,31407,28407,25407,19407,16407,13407,10407,7407,
    4407,1407,99407,96407,93407,90407,87407,84407,81407,78407
) GROUP BY product_id;

-- 핫스팟 1페이지 집계 시간 2회차.
SELECT product_id, COUNT(id) FROM product_like WHERE product_id IN (
    97407,94407,91407,88407,85407,82407,79407,76407,73407,70407,
    67407,64407,61407,58407,55407,52407,49407,46407,43407,40407
) GROUP BY product_id;

-- 일반 2페이지 집계 시간 2회차.
SELECT product_id, COUNT(id) FROM product_like WHERE product_id IN (
    37407,34407,31407,28407,25407,19407,16407,13407,10407,7407,
    4407,1407,99407,96407,93407,90407,87407,84407,81407,78407
) GROUP BY product_id;

-- 앞선 집계의 서버 Duration 비교.
SHOW PROFILES;

-- 핫스팟 Handler 시작값.
SHOW SESSION STATUS LIKE 'Handler_read%';

-- 핫스팟 1페이지 집계 실행.
SELECT product_id, COUNT(id) FROM product_like WHERE product_id IN (
    97407,94407,91407,88407,85407,82407,79407,76407,73407,70407,
    67407,64407,61407,58407,55407,52407,49407,46407,43407,40407
) GROUP BY product_id;

-- 핫스팟 Handler 종료값.
SHOW SESSION STATUS LIKE 'Handler_read%';

-- 일반 페이지 Handler 시작값.
SHOW SESSION STATUS LIKE 'Handler_read%';

-- 일반 2페이지 집계 실행.
SELECT product_id, COUNT(id) FROM product_like WHERE product_id IN (
    37407,34407,31407,28407,25407,19407,16407,13407,10407,7407,
    4407,1407,99407,96407,93407,90407,87407,84407,81407,78407
) GROUP BY product_id;

-- 일반 페이지 Handler 종료값.
SHOW SESSION STATUS LIKE 'Handler_read%';

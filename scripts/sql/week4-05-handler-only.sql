-- 05-handler-only: 대상 loopers_benchmark. 기록: ../../docs/week4/implementation-log.md (3절).
-- 실행 안내·기존 메모: ../../docs/week4/implementation-log.md#sql-reading-guide
-- 같은 연결에서 전체 실행하여 BEFORE/AFTER 차분 비교(자동 COUNT 없는 클라이언트).
USE loopers_benchmark;

-- 동일 세션인지 확인.
SELECT CONNECTION_ID() AS measurement_connection;

-- 측정 시작 표시.
SELECT 'BEFORE: 실행 전 누적값' AS measurement;

-- Handler 시작값.
SHOW SESSION STATUS LIKE 'Handler_read%';

-- 가격순 20개 조회의 Handler 증가량 측정.
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- 측정 종료 표시.
SELECT 'AFTER: 실행 후 누적값' AS measurement;

-- Handler 종료값: 시작값과 차분 비교.
SHOW SESSION STATUS LIKE 'Handler_read%';

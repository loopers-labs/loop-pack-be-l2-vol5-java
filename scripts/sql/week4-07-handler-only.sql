-- 07-handler-only: 대상 loopers_benchmark. 기록: ../../docs/week4/implementation-log.md (5절).
-- 실행 안내·기존 메모: ../../docs/week4/implementation-log.md#sql-reading-guide
-- 같은 연결에서 전체 실행하여 BEFORE/AFTER 차분 비교(자동 COUNT 없는 클라이언트).
USE loopers_benchmark;

-- 이름 포함 측정 시작.
SELECT 'WITH NAME - BEFORE' AS measurement;

-- 이름 포함 Handler 시작값.
SHOW SESSION STATUS LIKE 'Handler_read%';

-- 이름 포함 20개 조회 실행.
SELECT id, brand_id, name, price, created_at FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC LIMIT 20;

-- 이름 포함 측정 종료.
SELECT 'WITH NAME - AFTER' AS measurement;

-- 이름 포함 Handler 종료값.
SHOW SESSION STATUS LIKE 'Handler_read%';

-- 이름 제외 측정 시작.
SELECT 'WITHOUT NAME - BEFORE' AS measurement;

-- 이름 제외 Handler 시작값.
SHOW SESSION STATUS LIKE 'Handler_read%';

-- 이름 제외 20개 조회 실행.
SELECT id, brand_id, price, created_at FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC LIMIT 20;

-- 이름 제외 측정 종료.
SELECT 'WITHOUT NAME - AFTER' AS measurement;

-- 이름 제외 Handler 종료값.
SHOW SESSION STATUS LIKE 'Handler_read%';

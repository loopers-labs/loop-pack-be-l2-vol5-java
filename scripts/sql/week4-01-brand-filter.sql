-- 01-brand-filter: 대상 loopers_benchmark. 기록: ../../docs/week4/implementation-log.md (준비 측정).
-- 실행 안내·기존 메모: ../../docs/week4/implementation-log.md#sql-reading-guide
-- 기준선 조건: 기존 브랜드 인덱스만 있는 상태. 현재 최종 상태 비교는 18번.
USE loopers_benchmark;

-- 전체 미삭제 상품: 선택한 인덱스와 예상 읽기량.
EXPLAIN SELECT p.*
FROM product p
WHERE p.deleted_at IS NULL;

-- 브랜드 7 조건: 전체 조회와 예상 읽기량 비교.
EXPLAIN SELECT p.*
FROM product p
WHERE p.deleted_at IS NULL AND p.brand_id = 7;

-- 전체 미삭제 상품: 실제 읽은 행 수.
EXPLAIN ANALYZE SELECT p.*
FROM product p
WHERE p.deleted_at IS NULL;

-- 브랜드 7 조건: 실제 읽기 범위가 좁아지는지.
EXPLAIN ANALYZE SELECT p.*
FROM product p
WHERE p.deleted_at IS NULL AND p.brand_id = 7;

-- 전체 조회 Handler 시작값.
SHOW SESSION STATUS LIKE 'Handler_read%';

-- 전체 조회 실행.
SELECT p.* FROM product p
WHERE p.deleted_at IS NULL;

-- 전체 조회 Handler 종료값.
SHOW SESSION STATUS LIKE 'Handler_read%';

-- 브랜드 조회 Handler 시작값.
SHOW SESSION STATUS LIKE 'Handler_read%';

-- 브랜드 7 조회 실행.
SELECT p.* FROM product p
WHERE p.deleted_at IS NULL AND p.brand_id = 7;

-- 브랜드 조회 Handler 종료값.
SHOW SESSION STATUS LIKE 'Handler_read%';

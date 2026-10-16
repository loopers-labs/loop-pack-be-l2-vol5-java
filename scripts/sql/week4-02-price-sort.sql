-- 02-price-sort: 대상 loopers_benchmark. 기록: ../../docs/week4/implementation-log.md (1절).
-- 실행 안내·기존 메모: ../../docs/week4/implementation-log.md#sql-reading-guide
-- 기준선 조건: 기존 브랜드 인덱스만 있는 상태. 현재 최종 상태 비교는 18번.
USE loopers_benchmark;

-- 브랜드 7 전체 상품의 가격·동점 순서 확인.
SELECT p.*
FROM product p
WHERE p.deleted_at IS NULL
  AND p.brand_id = 7
ORDER BY p.price ASC, p.created_at DESC, p.id DESC;

-- ORDER BY 추가 후 filesort 여부.
EXPLAIN
SELECT p.*
FROM product p
WHERE p.deleted_at IS NULL
  AND p.brand_id = 7
ORDER BY p.price ASC, p.created_at DESC, p.id DESC;

-- 가격순 전체 조회의 실제 읽기량과 Sort 확인.
EXPLAIN ANALYZE
SELECT p.*
FROM product p
WHERE p.deleted_at IS NULL
  AND p.brand_id = 7
ORDER BY p.price ASC, p.created_at DESC, p.id DESC;

-- 가격순 조회 Handler 시작값.
SHOW SESSION STATUS LIKE 'Handler_read%';

-- LIMIT 없는 가격순 조회 실행.
SELECT p.*
FROM product p
WHERE p.deleted_at IS NULL
  AND p.brand_id = 7
ORDER BY p.price ASC, p.created_at DESC, p.id DESC;

-- 가격순 조회 Handler 종료값.
SHOW SESSION STATUS LIKE 'Handler_read%';

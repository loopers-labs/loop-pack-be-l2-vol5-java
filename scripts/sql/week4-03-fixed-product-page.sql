-- 03-fixed-product-page: 대상 loopers_benchmark. 기록: ../../docs/week4/implementation-log.md (2절).
-- 실행 안내·기존 메모: ../../docs/week4/implementation-log.md#sql-reading-guide
-- 기준선 조건: 기존 브랜드 인덱스만 있는 상태. 현재 최종 상태 비교는 18번.
USE loopers_benchmark;

-- 가격·생성 시각·ID 순서의 첫 20개 확인.
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- LIMIT 20에서도 별도 정렬이 필요한지.
EXPLAIN
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- 20개를 반환하려고 실제 몇 행을 읽는지.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- 페이지 조회 Handler 시작값.
SHOW SESSION STATUS LIKE 'Handler_read%';

-- 가격순 20개 조회 실행.
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- 페이지 조회 Handler 종료값.
SHOW SESSION STATUS LIKE 'Handler_read%';

-- 기존 브랜드 인덱스의 ID 순서와 가격 순서 비교.
SELECT brand_id, deleted_at, id, price
FROM product FORCE INDEX (idx_product_brand_deleted)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY id
LIMIT 10;

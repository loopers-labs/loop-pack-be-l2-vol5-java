-- 15-100k-scale-check: 대상 loopers_benchmark_100k. 기록: ../../docs/week4/implementation-log.md (12절).
-- 실행 안내·기존 메모: ../../docs/week4/implementation-log.md#sql-reading-guide
USE loopers_benchmark_100k;

-- 적재 후 상품 테이블 통계 상태.
SHOW TABLE STATUS LIKE 'product';

-- 통계 갱신 전: 선택 인덱스·예상 행 수.
EXPLAIN
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- 통계 갱신 전: 실제 20행·Sort 여부.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- 상품 통계 갱신.
ANALYZE TABLE product;

-- 통계 갱신 후: 인덱스 선택·예상 행 수 변화.
EXPLAIN
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- 통계 갱신 후: 실제 읽기량·Sort 유지 여부.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- 10만 건 페이지 Handler 시작값.
SHOW SESSION STATUS LIKE 'Handler_read%';

-- 브랜드 7 가격순 20개 조회 실행.
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- 10만 건 페이지 Handler 종료값.
SHOW SESSION STATUS LIKE 'Handler_read%';

-- 04-price-tie-index: 대상 loopers_benchmark. 기록: ../../docs/week4/implementation-log.md (3.1~3.2절).
-- 실행 안내·기존 메모: ../../docs/week4/implementation-log.md#sql-reading-guide
-- DDL 포함: 블록별 선택 실행. 이미 있는 인덱스/테이블은 CREATE를 생략한다.
USE loopers_benchmark;

-- 준비: 가격까지만 있는 후보 생성(없을 때).
CREATE INDEX idx_product_brand_price_only ON product (brand_id, deleted_at, price);

-- 후보 생성 후 통계 갱신.
ANALYZE TABLE product;

-- 힌트 없이 어떤 후보를 선택하고 정렬하는지.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product 
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- 현재 비교 가능한 인덱스 목록.
SHOW INDEX FROM product;

-- 가격까지만 강제: 동점 정렬과 읽기량.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product FORCE INDEX (idx_product_brand_price_only)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- 전체 정렬 인덱스 강제: 같은 20개를 적게 읽는지.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product FORCE INDEX (idx_product_brand_price)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- 07-covering: 대상 loopers_benchmark. 기록: ../../docs/week4/implementation-log.md (5절).
-- 실행 안내·기존 메모: ../../docs/week4/implementation-log.md#sql-reading-guide
-- DDL 포함: 블록별 선택 실행. 이미 있는 인덱스/테이블은 CREATE를 생략한다.
USE loopers_benchmark;

-- 이름 포함: Using index 여부.
EXPLAIN
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- 이름 포함: 실제 커버링·읽기 경로.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- 이름 제외: Using index 여부 비교.
EXPLAIN
SELECT id, brand_id, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- 이름 제외: 커버링 계획으로 바뀌는지.
EXPLAIN ANALYZE
SELECT id, brand_id, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- 준비: 이름까지 넣은 커버링 후보 생성.
CREATE INDEX idx_product_brand_price_name
ON product (brand_id, deleted_at, price ASC, created_at DESC, id DESC, name);

-- 이름을 반환해도 커버링되는지.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product FORCE INDEX (idx_product_brand_price_name)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- 정리: 이번에 만든 이름 포함 후보 제거.
DROP INDEX idx_product_brand_price_name ON product;

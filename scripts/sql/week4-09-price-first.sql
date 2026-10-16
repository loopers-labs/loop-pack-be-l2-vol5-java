-- 09-price-first: 대상 loopers_benchmark. 기록: ../../docs/week4/implementation-log.md (7절).
-- 실행 안내·기존 메모: ../../docs/week4/implementation-log.md#sql-reading-guide
-- DDL 포함: 블록별 선택 실행. 이미 있는 인덱스/테이블은 CREATE를 생략한다.
USE loopers_benchmark;

-- 준비: 가격을 첫 열로 둔 후보 생성.
CREATE INDEX idx_product_price_first
ON product (price ASC, brand_id, deleted_at, created_at DESC, id DESC);

-- 후보 통계 갱신.
ANALYZE TABLE product;

-- 가격 우선: 몇 행을 읽고, 다시 정렬하고, 20개를 반환하는지.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product FORCE INDEX (idx_product_price_first)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- 브랜드 우선: 같은 20개를 반환할 때 읽기량·추가 정렬 비교.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product FORCE INDEX (idx_product_brand_price)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- 힌트 없이 실제 선택하는 인덱스.
EXPLAIN
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- 정리: 이번에 만든 가격 우선 후보 제거.
DROP INDEX idx_product_price_first ON product;

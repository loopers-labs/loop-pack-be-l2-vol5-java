-- 06-implicit-id: 대상 loopers_benchmark. 기록: ../../docs/week4/implementation-log.md (4절).
-- 실행 안내·기존 메모: ../../docs/week4/implementation-log.md#sql-reading-guide
-- DDL 포함: 블록별 선택 실행. 이미 있는 인덱스/테이블은 CREATE를 생략한다.
USE loopers_benchmark;

-- 준비: id DESC 미명시 후보가 없을 때 생성.
CREATE INDEX idx_product_brand_price_time
ON product (brand_id, deleted_at, price ASC, created_at DESC);
ANALYZE TABLE product;

-- id DESC 미명시 후보: 동점에 Sort가 필요한지.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product FORCE INDEX (idx_product_brand_price_time)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- id DESC 포함 후보: Sort 없이 읽는지.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product FORCE INDEX (idx_product_brand_price)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- 보조 조회: 미명시 ID가 같은 가격·시각에서 ASC인지.
SELECT brand_id, deleted_at, price, created_at, id
FROM product FORCE INDEX (idx_product_brand_price_time)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id ASC
LIMIT 6;

-- 정리: 이번에 만든 미명시 ID 후보만 제거.
DROP INDEX idx_product_brand_price_time ON product;

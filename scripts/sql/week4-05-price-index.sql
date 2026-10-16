-- 05-price-index: 대상 loopers_benchmark. 기록: ../../docs/week4/implementation-log.md (3절).
-- 실행 안내·기존 메모: ../../docs/week4/implementation-log.md#sql-reading-guide
-- DDL 포함: 블록별 선택 실행. 이미 있는 인덱스/테이블은 CREATE를 생략한다.
USE loopers_benchmark;

-- 준비: 동점 정렬까지 맞춘 인덱스 생성(없을 때).
CREATE INDEX idx_product_brand_price
ON product (brand_id, deleted_at, price ASC, created_at DESC, id DESC);

-- 인덱스 생성 후 통계 갱신.
ANALYZE TABLE product;

-- 전체 정렬 인덱스의 자동 선택·filesort 여부.
EXPLAIN
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- 정렬 없이 20개만 읽는지.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 20;

-- 선택 정리: 04의 짧은 후보가 있을 때 제거.
DROP INDEX idx_product_brand_price_only ON product;

-- 짧은 후보 제거 후 실행 계획 유지 여부.
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

-- 가격·생성 시각·ID 인덱스 순서 확인.
SELECT brand_id, deleted_at, price, created_at, id
FROM product FORCE INDEX (idx_product_brand_price)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC
LIMIT 6;

-- 선택 초기화: 04로 되돌아갈 때만 전체 정렬 인덱스 제거.
DROP INDEX idx_product_brand_price ON product;

-- 11-deleted-condition: 대상 loopers_benchmark. 기록: ../../docs/week4/implementation-log.md (8.2절).
-- 실행 안내·기존 메모: ../../docs/week4/implementation-log.md#sql-reading-guide
-- DDL 포함: 블록별 선택 실행. 이미 있는 인덱스/테이블은 CREATE를 생략한다.
USE loopers_benchmark;

-- 준비: 삭제 조건 실험용 테이블 생성.
CREATE TABLE w4_product_deleted_lab LIKE product;

-- 원본 상품을 복제 테이블에 복사.
INSERT INTO w4_product_deleted_lab SELECT * FROM product;

-- deleted_at이 없는 비교 인덱스 생성.
CREATE INDEX idx_lab_without_deleted ON w4_product_deleted_lab
    (brand_id, price ASC, created_at DESC, id DESC);

-- 복제 테이블 통계 갱신.
ANALYZE TABLE w4_product_deleted_lab;

-- 원래 분포: 삭제 조건 미포함 인덱스의 읽기량.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM w4_product_deleted_lab FORCE INDEX (idx_lab_without_deleted)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC LIMIT 20;

-- 원래 분포: 삭제 조건 포함 인덱스의 읽기량.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM w4_product_deleted_lab FORCE INDEX (idx_product_brand_price)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC LIMIT 20;

-- 쏠림 준비: 복제의 싼 활성 상품 70개만 논리 삭제.
UPDATE w4_product_deleted_lab
SET deleted_at = '2026-10-14 00:00:00'
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC LIMIT 70;

-- 저가 상품 삭제 후: 조건 미포함 인덱스의 추가 읽기.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM w4_product_deleted_lab FORCE INDEX (idx_lab_without_deleted)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC LIMIT 20;

-- 저가 상품 삭제 후: 조건 포함 인덱스가 범위를 줄이는지.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at
FROM w4_product_deleted_lab FORCE INDEX (idx_product_brand_price)
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC LIMIT 20;

-- 정리: 이번에 만든 복제 테이블 제거.
DROP TABLE w4_product_deleted_lab;

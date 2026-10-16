-- 12-offset-cursor: 대상 loopers_benchmark. 기록: ../../docs/week4/implementation-log.md (8.3절).
-- 실행 안내·기존 메모: ../../docs/week4/implementation-log.md#sql-reading-guide
USE loopers_benchmark;

-- OFFSET 60: 건너뛸 행까지 실제로 읽는지.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC LIMIT 20 OFFSET 60;

-- 커서 기준: 60번째 상품의 가격·시각·ID 확인.
SELECT price, created_at, id FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
ORDER BY price ASC, created_at DESC, id DESC LIMIT 1 OFFSET 59;

-- 같은 다음 20개: 커서 조건으로 읽기량이 줄어드는지.
EXPLAIN ANALYZE
SELECT id, brand_id, name, price, created_at FROM product
WHERE brand_id = 7 AND deleted_at IS NULL
  AND (
      price > 66000
      OR (price = 66000 AND created_at < '2026-01-28 00:00:00')
      OR (price = 66000 AND created_at = '2026-01-28 00:00:00' AND id < 9207)
  )
ORDER BY price ASC, created_at DESC, id DESC LIMIT 20;

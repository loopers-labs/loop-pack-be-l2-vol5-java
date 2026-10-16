-- 14-page-first-join: 대상 loopers_benchmark. 기록: ../../docs/week4/implementation-log.md (10~11절).
-- 실행 안내·기존 메모: ../../docs/week4/implementation-log.md#sql-reading-guide
USE loopers_benchmark;

-- 페이지 20개를 먼저 고른 JOIN: 집계 행 수·최종 Sort 확인.
EXPLAIN ANALYZE
SELECT pg.id, pg.name, pg.price, pg.created_at,
       pg.brand_id, pg.brand_name, pg.brand_description,
       COUNT(l.id) AS like_count
FROM (
    SELECT p.id, p.name, p.price, p.created_at,
           b.id AS brand_id, b.name AS brand_name, b.description AS brand_description
    FROM product p
    JOIN brand b ON b.id = p.brand_id
    WHERE p.deleted_at IS NULL
      AND b.deleted_at IS NULL
      AND p.brand_id = 7
    ORDER BY p.price ASC, p.created_at DESC, p.id DESC
    LIMIT 20 OFFSET 0
) AS pg
LEFT JOIN product_like l ON l.product_id = pg.id
GROUP BY pg.id, pg.name, pg.price, pg.created_at,
         pg.brand_id, pg.brand_name, pg.brand_description
ORDER BY pg.price ASC, pg.created_at DESC, pg.id DESC;

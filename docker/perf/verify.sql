-- =====================================================================
-- 4주차 시드 검증 — seed.sql 실행 뒤 같은 DB에서 실행한다
-- 실행: docker exec -i loopers-perf-mysql mysql -uapplication -papplication -t loopers_perf < docker/perf/verify.sql
--
-- [1] 행 수  [2] 규칙 위반(모두 0)  [3] 브랜드 규모  [4] 측정 후보 브랜드
-- [5] 가격 분포  [6] 좋아요 분포  [7] 재실행 비교용 체크섬
-- "활성"은 deleted_at IS NULL — 고객 조회에 노출되는 행이다.
-- =====================================================================
USE loopers_perf;

-- [1] 테이블별 전체·활성·삭제 행 수
SELECT 'brand' AS tbl, COUNT(*) AS total, SUM(deleted_at IS NULL) AS active, SUM(deleted_at IS NOT NULL) AS deleted
FROM brand
UNION ALL
SELECT 'product', COUNT(*), SUM(deleted_at IS NULL), SUM(deleted_at IS NOT NULL) FROM product
UNION ALL
SELECT 'product_like', COUNT(*), SUM(deleted_at IS NULL), SUM(deleted_at IS NOT NULL) FROM product_like;

-- [2] 도메인 규칙 위반 — 모두 0이어야 한다
SELECT
    (SELECT COUNT(*) FROM brand WHERE name IS NULL OR TRIM(name) = '') AS brand_blank_name,
    (SELECT COUNT(*) FROM product WHERE name IS NULL OR TRIM(name) = '' OR CHAR_LENGTH(name) > 100)
        AS product_bad_name,
    (SELECT COUNT(*) FROM product WHERE price IS NULL OR price < 1 OR price > 100000000) AS product_bad_price,
    (SELECT COUNT(*) FROM product WHERE stock IS NULL OR stock < 0) AS product_bad_stock,
    (SELECT COUNT(*) FROM product p LEFT JOIN brand b ON b.id = p.brand_id WHERE b.id IS NULL) AS product_no_brand,
    (SELECT COUNT(*)
     FROM product p
     JOIN brand b ON b.id = p.brand_id
     WHERE b.deleted_at IS NOT NULL AND p.deleted_at IS NULL) AS active_product_of_closed_brand,
    (SELECT COUNT(*) FROM product_like l LEFT JOIN product p ON p.id = l.product_id WHERE p.id IS NULL)
        AS like_no_product,
    (SELECT COUNT(*) FROM (SELECT 1 FROM product_like GROUP BY user_id, product_id HAVING COUNT(*) > 1) d)
        AS like_duplicate,
    (SELECT COUNT(*) FROM product WHERE deleted_at < created_at)
        + (SELECT COUNT(*) FROM product_like WHERE deleted_at < created_at) AS deleted_before_created;

-- [3] 브랜드 규모 — 활성 브랜드의 활성 상품 수 (구분은 seed.sql의 브랜드 id 구간)
SELECT tier,
       COUNT(*) AS brands,
       MIN(active_products) AS min_products,
       ROUND(AVG(active_products)) AS avg_products,
       MAX(active_products) AS max_products,
       SUM(active_products) AS total_products
FROM (
    SELECT b.id,
           CASE WHEN b.id <= 20 THEN '1 대형' WHEN b.id <= 150 THEN '2 중형' ELSE '3 소형' END AS tier,
           COUNT(p.id) AS active_products
    FROM brand b
    LEFT JOIN product p ON p.brand_id = b.id AND p.deleted_at IS NULL
    WHERE b.deleted_at IS NULL
    GROUP BY b.id
) t
GROUP BY tier
ORDER BY tier;

-- [4] 측정 후보 브랜드 — 활성 상품이 많은 대형 5개와 소형 구간(id 151~)의 앞 3개
--     price_sum: 활성 상품 가격 합계 (인덱스 전후 결과 비교 기준값)
--     distinct_prices / max_same_price: 서로 다른 가격 수 / 같은 가격 상품이 가장 많은 그룹의 크기
WITH brand_stats AS (
    SELECT brand_id,
           SUM(cnt) AS active_products,
           SUM(price * cnt) AS price_sum,
           COUNT(*) AS distinct_prices,
           MAX(cnt) AS max_same_price
    FROM (
        SELECT brand_id, price, COUNT(*) AS cnt
        FROM product
        WHERE deleted_at IS NULL
        GROUP BY brand_id, price
    ) x
    GROUP BY brand_id
)
(SELECT '대형' AS pick, b.id, b.name, s.active_products, s.price_sum, s.distinct_prices, s.max_same_price
 FROM brand b JOIN brand_stats s ON s.brand_id = b.id
 WHERE b.deleted_at IS NULL
 ORDER BY s.active_products DESC, b.id
 LIMIT 5)
UNION ALL
(SELECT '소형', b.id, b.name, s.active_products, s.price_sum, s.distinct_prices, s.max_same_price
 FROM brand b JOIN brand_stats s ON s.brand_id = b.id
 WHERE b.deleted_at IS NULL AND b.id > 150
 ORDER BY b.id
 LIMIT 3);

-- [5] 가격 분포 — 활성 상품
SELECT CASE WHEN price < 10000 THEN '1 ~9,999'
            WHEN price < 50000 THEN '2 10,000~49,999'
            WHEN price < 100000 THEN '3 50,000~99,999'
            WHEN price < 500000 THEN '4 100,000~499,999'
            ELSE '5 500,000~' END AS price_range,
       COUNT(*) AS products
FROM product
WHERE deleted_at IS NULL
GROUP BY price_range
ORDER BY price_range;

SELECT COUNT(*) AS distinct_prices, MAX(cnt) AS max_same_price
FROM (SELECT price, COUNT(*) AS cnt FROM product WHERE deleted_at IS NULL GROUP BY price) x;

-- [6] 좋아요 분포 — 활성 상품별 활성 좋아요 수 (0개·동점·인기 상품 확인)
WITH like_counts AS (
    SELECT p.id, COALESCE(l.cnt, 0) AS cnt
    FROM product p
    LEFT JOIN (
        SELECT product_id, COUNT(*) AS cnt
        FROM product_like
        WHERE deleted_at IS NULL
        GROUP BY product_id
    ) l ON l.product_id = p.id
    WHERE p.deleted_at IS NULL
)
SELECT CASE WHEN cnt = 0 THEN '1 0개'
            WHEN cnt <= 10 THEN '2 1~10개'
            WHEN cnt <= 50 THEN '3 11~50개'
            WHEN cnt <= 300 THEN '4 51~300개'
            ELSE '5 301개~' END AS like_range,
       COUNT(*) AS products,
       MIN(cnt) AS min_likes,
       MAX(cnt) AS max_likes
FROM like_counts
GROUP BY like_range
ORDER BY like_range;

WITH like_counts AS (
    SELECT p.id, COALESCE(l.cnt, 0) AS cnt
    FROM product p
    LEFT JOIN (
        SELECT product_id, COUNT(*) AS cnt
        FROM product_like
        WHERE deleted_at IS NULL
        GROUP BY product_id
    ) l ON l.product_id = p.id
    WHERE p.deleted_at IS NULL
)
SELECT COUNT(*) AS distinct_like_counts, MAX(products) AS max_same_like_count
FROM (SELECT cnt, COUNT(*) AS products FROM like_counts GROUP BY cnt) x;

-- [7] 재실행 비교용 체크섬 — seed.sql을 다시 돌려도 값이 같아야 한다 (행 순서와 무관한 BIT_XOR)
SELECT 'brand' AS tbl,
       COUNT(*) AS total,
       BIT_XOR(CRC32(CONCAT_WS('|', id, name, created_at, IFNULL(deleted_at, '-')))) AS checksum
FROM brand
UNION ALL
SELECT 'product',
       COUNT(*),
       BIT_XOR(CRC32(CONCAT_WS('|', id, brand_id, name, price, stock, created_at, IFNULL(deleted_at, '-'))))
FROM product
UNION ALL
SELECT 'product_like',
       COUNT(*),
       BIT_XOR(CRC32(CONCAT_WS('|', id, user_id, product_id, created_at, IFNULL(deleted_at, '-'))))
FROM product_like;

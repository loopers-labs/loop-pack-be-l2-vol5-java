-- 1단계 고정 요청: brandId=7, page=0, size=20, sort=price_asc.
-- 상품·브랜드 조회의 별칭을 읽기 쉽게 정리했고, Native UNION ALL의 ID 바인딩값을 대입했다.

SELECT p.id, p.brand_id, p.created_at, p.deleted_at,
       p.name, p.price, p.stock, p.updated_at
FROM products p
LEFT JOIN brands b ON b.id = p.brand_id
WHERE p.deleted_at IS NULL AND b.id = 7
ORDER BY p.price, p.id
LIMIT 20;

SELECT b.id, b.created_at, b.deleted_at, b.name, b.updated_at
FROM brands b
WHERE b.id IN (7);

SELECT 5406 AS product_id, COUNT(l.id) AS like_count FROM likes l WHERE l.product_id = 5406
UNION ALL
SELECT 105406 AS product_id, COUNT(l.id) AS like_count FROM likes l WHERE l.product_id = 105406
UNION ALL
SELECT 205406 AS product_id, COUNT(l.id) AS like_count FROM likes l WHERE l.product_id = 205406
UNION ALL
SELECT 305406 AS product_id, COUNT(l.id) AS like_count FROM likes l WHERE l.product_id = 305406
UNION ALL
SELECT 405406 AS product_id, COUNT(l.id) AS like_count FROM likes l WHERE l.product_id = 405406
UNION ALL
SELECT 505406 AS product_id, COUNT(l.id) AS like_count FROM likes l WHERE l.product_id = 505406
UNION ALL
SELECT 605406 AS product_id, COUNT(l.id) AS like_count FROM likes l WHERE l.product_id = 605406
UNION ALL
SELECT 705406 AS product_id, COUNT(l.id) AS like_count FROM likes l WHERE l.product_id = 705406
UNION ALL
SELECT 805406 AS product_id, COUNT(l.id) AS like_count FROM likes l WHERE l.product_id = 805406
UNION ALL
SELECT 905406 AS product_id, COUNT(l.id) AS like_count FROM likes l WHERE l.product_id = 905406
UNION ALL
SELECT 2706 AS product_id, COUNT(l.id) AS like_count FROM likes l WHERE l.product_id = 2706
UNION ALL
SELECT 102706 AS product_id, COUNT(l.id) AS like_count FROM likes l WHERE l.product_id = 102706
UNION ALL
SELECT 202706 AS product_id, COUNT(l.id) AS like_count FROM likes l WHERE l.product_id = 202706
UNION ALL
SELECT 302706 AS product_id, COUNT(l.id) AS like_count FROM likes l WHERE l.product_id = 302706
UNION ALL
SELECT 402706 AS product_id, COUNT(l.id) AS like_count FROM likes l WHERE l.product_id = 402706
UNION ALL
SELECT 502706 AS product_id, COUNT(l.id) AS like_count FROM likes l WHERE l.product_id = 502706
UNION ALL
SELECT 602706 AS product_id, COUNT(l.id) AS like_count FROM likes l WHERE l.product_id = 602706
UNION ALL
SELECT 702706 AS product_id, COUNT(l.id) AS like_count FROM likes l WHERE l.product_id = 702706
UNION ALL
SELECT 802706 AS product_id, COUNT(l.id) AS like_count FROM likes l WHERE l.product_id = 802706
UNION ALL
SELECT 902706 AS product_id, COUNT(l.id) AS like_count FROM likes l WHERE l.product_id = 902706;

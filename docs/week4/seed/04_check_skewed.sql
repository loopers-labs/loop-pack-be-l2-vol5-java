ANALYZE TABLE brand, product, product_like;

SELECT COUNT(*)                                    AS total_products,
       SUM(deleted_at IS NULL)                     AS alive_products,
       ROUND(100 * AVG(deleted_at IS NOT NULL), 2) AS deleted_pct
  FROM product;

SELECT brand_id,
       COUNT(*)                AS products,
       SUM(deleted_at IS NULL) AS alive_products
  FROM product
 WHERE brand_id IN (1, 100, 1000, 300, 700)
 GROUP BY brand_id
 ORDER BY brand_id;

SELECT COUNT(*) AS alive_products_under_deleted_brands
  FROM product p
  JOIN brand b ON b.id = p.brand_id
 WHERE b.deleted_at IS NOT NULL
   AND p.deleted_at IS NULL;

SELECT SUM(likes)                                              AS total_likes,
       ROUND(SUM(CASE WHEN rn <= cnt DIV 100 THEN likes END) / SUM(likes), 3) AS top1pct_share,
       ROUND(AVG(likes = 0), 3)                                AS zero_like_ratio,
       MAX(likes)                                              AS max_likes
  FROM (
      SELECT c.likes,
             ROW_NUMBER() OVER (ORDER BY c.likes DESC, c.id) AS rn,
             COUNT(*) OVER ()                                AS cnt
        FROM (
            SELECT p.id, COUNT(l.id) AS likes
              FROM product p
              LEFT JOIN product_like l ON l.product_id = p.id
             GROUP BY p.id
        ) c
  ) ranked;

SELECT MIN(price)                                  AS price_min,
       MAX(price)                                  AS price_max,
       MAX(CASE WHEN rn = cnt DIV 2 + 1 THEN price END) AS price_median,
       COUNT(DISTINCT price)                       AS distinct_prices
  FROM (
      SELECT price,
             ROW_NUMBER() OVER (ORDER BY price) AS rn,
             COUNT(*) OVER ()                   AS cnt
        FROM product
       WHERE deleted_at IS NULL
  ) ranked;
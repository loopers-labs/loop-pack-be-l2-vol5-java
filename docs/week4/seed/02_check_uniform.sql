CALL seed_uniform();

ANALYZE TABLE brand, product, product_like;

SELECT COUNT(*)              AS product_count,
       10000                 AS expected_product_count,
       SUM(price)            AS price_sum,
       472685000             AS expected_price_sum
  FROM product
 WHERE deleted_at IS NULL;

SELECT COUNT(*)              AS brand7_count,
       100                   AS expected_brand7_count,
       SUM(price)            AS brand7_price_sum,
       4647200               AS expected_brand7_price_sum
  FROM product
 WHERE brand_id = 7
   AND deleted_at IS NULL;

SELECT COUNT(*)              AS like_count,
       78000                 AS expected_like_count
  FROM product_like;

SELECT SUM(c.likes = 0)      AS zero_like_products,
       2490                  AS expected_zero_like_products,
       MAX(c.likes)          AS max_likes,
       300                   AS expected_max_likes
  FROM (
      SELECT p.id, COUNT(l.id) AS likes
        FROM product p
        LEFT JOIN product_like l ON l.product_id = p.id
       GROUP BY p.id
  ) c;

SELECT COUNT(DISTINCT price) AS distinct_prices,
       10000                 AS expected_distinct_prices
  FROM product;
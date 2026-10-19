SELECT UTC_TIMESTAMP() AS captured_at_utc,
       VERSION() AS mysql_version,
       @@innodb_buffer_pool_size AS innodb_buffer_pool_bytes,
       @@transaction_isolation AS transaction_isolation,
       @@performance_schema AS performance_schema_enabled;

SELECT 'brands' AS entity, COUNT(*) AS total FROM brands
UNION ALL SELECT 'users', COUNT(*) FROM users
UNION ALL SELECT 'products', COUNT(*) FROM products
UNION ALL SELECT 'likes', COUNT(*) FROM likes;

SELECT COUNT(*) AS product_count,
       SUM(price) AS price_sum,
       SUM(deleted_at IS NOT NULL) AS deleted_products
FROM products;

SELECT brand_id, COUNT(*) AS product_count, SUM(price) AS price_sum
FROM products
WHERE brand_id = 7
GROUP BY brand_id;

SELECT TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME, NON_UNIQUE
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name IN ('brands', 'products', 'likes')
ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX;

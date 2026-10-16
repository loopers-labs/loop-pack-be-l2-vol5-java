SELECT 'brand.name', COUNT(*) FROM brand
WHERE name IS NULL OR NOT (OCTET_LENGTH(CONVERT(name USING utf16)) BETWEEN 2 AND 100
    AND ASCII(LEFT(name, 1)) > 32 AND ASCII(RIGHT(name, 1)) > 32)
UNION ALL
SELECT 'product.name', COUNT(*) FROM product
WHERE name IS NULL OR NOT (OCTET_LENGTH(CONVERT(name USING utf16)) BETWEEN 2 AND 200
    AND ASCII(LEFT(name, 1)) > 32 AND ASCII(RIGHT(name, 1)) > 32)
UNION ALL
SELECT 'product.price', COUNT(*) FROM product WHERE price IS NULL OR price NOT BETWEEN 1 AND 1000000000
UNION ALL
SELECT 'product.brand_id', COUNT(*) FROM product WHERE brand_id IS NULL
UNION ALL
SELECT 'stock.quantity', COUNT(*) FROM stock WHERE quantity IS NULL OR quantity < 0
UNION ALL
SELECT 'product_like.references', COUNT(*) FROM product_like WHERE user_id IS NULL OR product_id IS NULL
UNION ALL
SELECT 'brand.active_name', COUNT(*) FROM (
    SELECT CONVERT(name USING binary) FROM brand WHERE deleted_at IS NULL
    GROUP BY CONVERT(name USING binary) HAVING COUNT(*) > 1
) duplicates
UNION ALL
SELECT 'product.active_name', COUNT(*) FROM (
    SELECT brand_id, CONVERT(name USING binary) FROM product WHERE deleted_at IS NULL
    GROUP BY brand_id, CONVERT(name USING binary) HAVING COUNT(*) > 1
) duplicates;

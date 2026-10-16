-- Actual customer detail SQL. Bound parameters: 7 (BIGINT), false (BOOLEAN), false (BOOLEAN).
SELECT pje1_0.id,pje1_0.name,pje1_0.price,bje1_0.id,bje1_0.name,pje1_0.like_count,
       pje1_0.description,pje1_0.stock,pje1_0.created_at
FROM products pje1_0
JOIN brands bje1_0 ON bje1_0.id=pje1_0.brand_id
WHERE pje1_0.id=7 AND pje1_0.deleted=false AND bje1_0.deleted=false;

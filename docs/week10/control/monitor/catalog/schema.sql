USE loopers;

CREATE TABLE IF NOT EXISTS w10_catalog_reads (
    bucket_at DATETIME(6) NOT NULL,
    product_id BIGINT NOT NULL,
    kind VARCHAR(32) NOT NULL,
    hits BIGINT UNSIGNED NOT NULL,
    errors BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (bucket_at, product_id, kind),
    CHECK (kind IN ('detail','revisit')),
    CHECK (errors <= hits)
);

CREATE OR REPLACE VIEW w10_catalog_products AS
SELECT p.id AS product_id, p.name AS product_name, c.category, c.popularity_tier
FROM w10_catalog c JOIN products p ON p.id = c.product_id;

CREATE OR REPLACE VIEW w10_catalog_mixed_lines AS
SELECT o.id AS order_id, o.created_at AS ordered_at, p.created_at AS accepted_at,
       r.created_at AS confirmed_at, i.product_id, i.quantity,
       COALESCE(c.category, '미분류') AS category
FROM orders o
JOIN order_items i ON i.order_id = o.id
LEFT JOIN payment_attempts p ON p.order_id = o.id
LEFT JOIN order_records r ON r.order_id = o.id AND r.status = 'PAID'
LEFT JOIN w10_catalog c ON c.product_id = i.product_id
WHERE o.user_id BETWEEN 2001 AND 2400;

CREATE OR REPLACE VIEW w10_catalog_mixed_orders AS
SELECT order_id, ordered_at, accepted_at, confirmed_at,
       COUNT(DISTINCT product_id) AS sku_count, SUM(quantity) AS units
FROM w10_catalog_mixed_lines
GROUP BY order_id, ordered_at, accepted_at, confirmed_at;

GRANT SELECT ON loopers.w10_catalog_reads TO 'w10_grafana'@'%';
GRANT SELECT ON loopers.w10_catalog_products TO 'w10_grafana'@'%';
GRANT SELECT ON loopers.w10_catalog_mixed_orders TO 'w10_grafana'@'%';
GRANT SELECT ON loopers.w10_catalog_mixed_lines TO 'w10_grafana'@'%';

USE loopers;

CREATE TABLE IF NOT EXISTS w10_control_phases (
    phase VARCHAR(16) PRIMARY KEY,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
    CHECK (phase IN ('normal','comparison','recovery')),
    CHECK (starts_at < ends_at)
);

CREATE TABLE IF NOT EXISTS w10_control_samples (
    sampled_at DATETIME(6) PRIMARY KEY,
    active_readers INT UNSIGNED NOT NULL,
    product_requests BIGINT UNSIGNED NOT NULL,
    product_errors BIGINT UNSIGNED NOT NULL,
    water_sent BIGINT UNSIGNED NOT NULL,
    process_cpu_pct DOUBLE NULL
);

CREATE OR REPLACE VIEW w10_control_water_orders AS
SELECT p.order_id, p.attempt_key, p.created_at AS accepted_at, r.created_at AS confirmed_at,
       CASE WHEN r.created_at BETWEEN p.created_at AND p.created_at + INTERVAL 30 SECOND
            THEN 1 ELSE 0 END AS within_30s
FROM payment_attempts p
JOIN orders o ON o.id = p.order_id AND o.user_id = 1001
LEFT JOIN order_records r ON r.order_id = p.order_id AND r.status = 'PAID'
WHERE EXISTS (SELECT 1 FROM order_items i WHERE i.order_id = p.order_id AND i.product_id = 3);

CREATE OR REPLACE VIEW w10_control_phase_sli AS
SELECT ph.phase, ph.starts_at, ph.ends_at,
       COUNT(w.order_id) AS accepted,
       COUNT(CASE WHEN w.accepted_at <= UTC_TIMESTAMP(6) - INTERVAL 30 SECOND THEN 1 END) AS matured,
       COUNT(CASE WHEN w.within_30s = 1 THEN 1 END) AS within_30s,
       CASE WHEN UTC_TIMESTAMP(6) >= ph.ends_at + INTERVAL 30 SECOND
            THEN ROUND(100.0 * COUNT(CASE WHEN w.within_30s = 1 THEN 1 END) / NULLIF(COUNT(w.order_id), 0), 2)
            END AS final_pct
FROM w10_control_phases ph
LEFT JOIN w10_control_water_orders w ON w.accepted_at >= ph.starts_at AND w.accepted_at < ph.ends_at
GROUP BY ph.phase, ph.starts_at, ph.ends_at;

CREATE USER IF NOT EXISTS 'w10_grafana'@'%' IDENTIFIED BY 'week10-local-readonly';
GRANT SELECT ON loopers.w10_control_phases TO 'w10_grafana'@'%';
GRANT SELECT ON loopers.w10_control_samples TO 'w10_grafana'@'%';
GRANT SELECT ON loopers.w10_control_phase_sli TO 'w10_grafana'@'%';
GRANT SELECT ON loopers.w10_control_water_orders TO 'w10_grafana'@'%';

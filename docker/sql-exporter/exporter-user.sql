CREATE USER IF NOT EXISTS 'exporter'@'%' IDENTIFIED BY 'exporter' WITH MAX_USER_CONNECTIONS 2;
GRANT SELECT (id, status) ON loopers.payment TO 'exporter'@'%';
GRANT SELECT (id, status) ON loopers.order_event_outbox TO 'exporter'@'%';

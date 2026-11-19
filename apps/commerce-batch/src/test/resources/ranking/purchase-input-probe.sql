-- Private C03 fixture only. Never prepare this table in an application database.
CREATE TABLE batch_restart_probe (
    snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    product_id BIGINT NOT NULL,
    PRIMARY KEY (snapshot_id, product_id)
) ENGINE=InnoDB;

INSERT INTO order_confirmation_inbox
    (id, event_id, order_id, event_type, schema_version, confirmed_at, normalized_payload, processed_at)
VALUES
    (42, '00000000-0000-0000-0000-000000000042', 42, 'OrderConfirmed', 1, '2026-08-17 01:00:00', '{}', '2026-08-17 01:00:00'),
    (43, '00000000-0000-0000-0000-000000000043', 43, 'OrderConfirmed', 1, '2026-08-18 01:00:00', '{}', '2026-08-18 01:00:00'),
    (44, '00000000-0000-0000-0000-000000000044', 44, 'OrderConfirmed', 1, '2026-08-19 01:00:00', '{}', '2026-08-19 01:00:00'),
    (45, '00000000-0000-0000-0000-000000000045', 45, 'OrderConfirmed', 1, '2026-08-10 01:00:00', '{}', '2026-08-10 01:00:00');

INSERT INTO product_purchase_activities (inbox_id, order_id, product_id, quantity, confirmed_at)
VALUES
    (42, 42, 7, 2, '2026-08-17 01:00:00'),
    (42, 42, 8, 1, '2026-08-17 01:00:00'),
    (43, 43, 7, 3, '2026-08-18 01:00:00'),
    (44, 44, 9, 5, '2026-08-19 01:00:00'),
    (45, 45, 6, 99, '2026-08-10 01:00:00');

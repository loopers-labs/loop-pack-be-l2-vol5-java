-- Commerce와 배치 등 DB 쓰기를 멈춘 상태에서 기존 PG 스키마에 한 번 적용한다.
-- POINT 전용 DB는 pg-payment-migration.sql을 먼저 적용한다. 새 DB는 init.sql만 사용한다.
ALTER TABLE loopers.order_items ADD INDEX idx_order_items_product_id (product_id);

-- 아래 조회가 행을 반환하면 재고·예약 정합성을 먼저 확인한다. 수량을 임의 절삭하지 않는다.
SELECT p.id, p.stock, COALESCE(r.quantity, 0) AS pending_reserved_quantity
FROM loopers.products p
LEFT JOIN (
    SELECT i.product_id, SUM(i.quantity) AS quantity
    FROM loopers.order_items i
    JOIN loopers.orders o ON o.id = i.order_id
    WHERE o.status = 'PAYMENT_PENDING' AND o.payment_method = 'PG'
    GROUP BY i.product_id
) r ON r.product_id = p.id
WHERE p.stock < 0 OR COALESCE(r.quantity, 0) > 9223372036854775807 - p.stock;

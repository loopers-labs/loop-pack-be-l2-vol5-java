-- 대사 (plan.md D-8). 읽기만 한다. 한 줄 = 항목 · 값.
-- 입력: @fault_ids = 장애 집합 주문 ID (쉼표로 이은 문자열, 예 '11,12,13'), @product_id = 상품 ID
--   mysql> SET @fault_ids = '11,12,13,14,15,16,17,18,19,20'; SET @product_id = 1; SOURCE reconcile-check.sql;
-- fault.* = 장애 집합 10개만, all.* = 이 DB 전체.
SELECT 'fault.payment.SUCCESS' AS item, COUNT(*) AS value FROM payments WHERE FIND_IN_SET(order_id, @fault_ids) AND status = 'SUCCESS'
UNION ALL SELECT 'fault.payment.UNKNOWN', COUNT(*) FROM payments WHERE FIND_IN_SET(order_id, @fault_ids) AND status = 'UNKNOWN'
UNION ALL SELECT 'fault.payment.PENDING', COUNT(*) FROM payments WHERE FIND_IN_SET(order_id, @fault_ids) AND status = 'PENDING'
UNION ALL SELECT 'fault.payment.transaction_key_null', COUNT(*) FROM payments WHERE FIND_IN_SET(order_id, @fault_ids) AND transaction_key IS NULL
UNION ALL SELECT 'fault.amount_mismatch', COUNT(*)
            FROM orders o JOIN payments p ON p.order_id = o.id
           WHERE FIND_IN_SET(o.id, @fault_ids) AND (o.total_amount <> p.amount OR (o.paid_amount IS NOT NULL AND o.paid_amount <> o.total_amount))
UNION ALL SELECT 'fault.order.CONFIRMED', COUNT(*) FROM orders WHERE FIND_IN_SET(id, @fault_ids) AND status = 'CONFIRMED'
UNION ALL SELECT 'fault.order.PAYMENT_PENDING', COUNT(*) FROM orders WHERE FIND_IN_SET(id, @fault_ids) AND status = 'PAYMENT_PENDING'
UNION ALL SELECT 'fault.order.confirmed_at_null', COUNT(*) FROM orders WHERE FIND_IN_SET(id, @fault_ids) AND confirmed_at IS NULL
UNION ALL SELECT 'fault.reservation.RESERVED', COUNT(*) FROM stock_reservations WHERE FIND_IN_SET(order_id, @fault_ids) AND status = 'RESERVED'
UNION ALL SELECT 'fault.reservation.CONSUMED', COUNT(*) FROM stock_reservations WHERE FIND_IN_SET(order_id, @fault_ids) AND status = 'CONSUMED'
UNION ALL SELECT 'fault.outbox.rows', COUNT(*) FROM order_outbox WHERE FIND_IN_SET(order_id, @fault_ids)
UNION ALL SELECT 'fault.outbox.published', COUNT(*) FROM order_outbox WHERE FIND_IN_SET(order_id, @fault_ids) AND status = 'PUBLISHED'
UNION ALL SELECT 'fault.outbox.max_rows_per_order', COALESCE(MAX(c), 0)
            FROM (SELECT order_id, COUNT(*) AS c FROM order_outbox WHERE FIND_IN_SET(order_id, @fault_ids) GROUP BY order_id) t
UNION ALL SELECT 'fault.activity.rows', COUNT(*) FROM product_purchase_activity WHERE FIND_IN_SET(order_id, @fault_ids)
UNION ALL SELECT 'fault.activity.quantity_sum', COALESCE(SUM(quantity), 0) FROM product_purchase_activity WHERE FIND_IN_SET(order_id, @fault_ids)
UNION ALL SELECT 'fault.activity.max_rows_per_order', COALESCE(MAX(c), 0)
            FROM (SELECT order_id, COUNT(*) AS c FROM product_purchase_activity WHERE FIND_IN_SET(order_id, @fault_ids) GROUP BY order_id) t
UNION ALL SELECT 'fault.activity.confirmed_at_mismatch', COUNT(*)
            FROM product_purchase_activity a JOIN orders o ON o.id = a.order_id
           WHERE FIND_IN_SET(a.order_id, @fault_ids) AND a.confirmed_at <> o.confirmed_at
UNION ALL SELECT 'all.order.CONFIRMED', COUNT(*) FROM orders WHERE status = 'CONFIRMED'
UNION ALL SELECT 'all.payment.UNKNOWN', COUNT(*) FROM payments WHERE status = 'UNKNOWN'
UNION ALL SELECT 'all.payment.PENDING', COUNT(*) FROM payments WHERE status = 'PENDING'
UNION ALL SELECT 'all.reservation.RESERVED', COUNT(*) FROM stock_reservations WHERE status = 'RESERVED'
UNION ALL SELECT 'all.reservation.CONSUMED', COUNT(*) FROM stock_reservations WHERE status = 'CONSUMED'
UNION ALL SELECT 'all.outbox.rows', COUNT(*) FROM order_outbox
UNION ALL SELECT 'all.activity.rows', COUNT(*) FROM product_purchase_activity
UNION ALL SELECT 'all.confirmed_weeks', COUNT(DISTINCT YEARWEEK(confirmed_at, 3)) FROM orders WHERE confirmed_at IS NOT NULL
UNION ALL SELECT 'product.stock', stock FROM products WHERE id = @product_id
UNION ALL SELECT 'point_histories.rows', COUNT(*) FROM point_histories
UNION ALL SELECT 'user_coupon.rows', COUNT(*) FROM user_coupon;

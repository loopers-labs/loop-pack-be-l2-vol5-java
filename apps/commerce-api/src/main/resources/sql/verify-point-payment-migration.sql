-- 주문의 옛 결제 열 삭제 전 이관 검증 (설계 22절)
-- 실행 순서: migrate-point-payments.sql → 이 스크립트 → drop-legacy-order-payment-columns.sql
-- 통과 조건: legacy_paid_orders = point_records, legacy_amount = point_amount, mismatched_orders = 0.
-- 하나라도 어긋나면 삭제 스크립트를 실행하지 않는다. 옛 열을 읽으므로 삭제 전에만 실행할 수 있다.
-- 대상은 옛 열에 결제 결과(SUCCESS)가 있는 주문이다. 2a 이후 confirm이 만든 POINT 기록은 옛 열이 기본값이라 세지 않는다.
SELECT COUNT(*) AS legacy_paid_orders,
       COUNT(p.id) AS point_records,
       COALESCE(SUM(o.paid_amount), 0) AS legacy_amount,
       COALESCE(SUM(p.amount), 0) AS point_amount,
       COALESCE(SUM(CASE WHEN p.id IS NULL OR p.amount <> o.paid_amount THEN 1 ELSE 0 END), 0) AS mismatched_orders
FROM orders o
LEFT JOIN payment_records p ON p.order_id = o.id AND p.method = 'POINT' AND p.result = 'SUCCESS'
WHERE o.payment_result = 'SUCCESS'

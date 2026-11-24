-- 진단 ② 결과를 모르는 결제 목록 (plan.md D-6). 읽기만 한다.
-- 한 줄 = 결제 시도 하나. 주문 · 결제 시도 · PG 주문 식별자 · 거래 키 · 재고 선점을 함께 본다.
SELECT p.order_id,
       p.id                 AS payment_id,
       p.provider_order_id,
       p.status             AS payment_status,
       COALESCE(p.transaction_key, 'NULL') AS transaction_key,
       p.amount,
       p.sent_at,
       COALESCE(p.last_check_result, '-') AS last_check_result,
       o.status             AS order_status,
       r.status             AS reservation_status
  FROM payments p
  JOIN orders o ON o.id = p.order_id
  LEFT JOIN stock_reservations r ON r.order_id = p.order_id
 WHERE p.status IN ('PENDING', 'UNKNOWN')
 ORDER BY p.sent_at, p.id;

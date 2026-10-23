-- 부하가 끝난 뒤 규칙이 지켜졌는지 확인한다. 위 6개는 "규칙이 깨진 상태"를 세므로 모두 0이어야 한다.
-- seed 프로필 기준: 상품 초기 재고 100, 사용자 포인트 1,000,000. 4번(create 수)은 테스트 PG의 요청 기록과 비교한다.

-- 1. 재고 = 초기 재고 − 결제가 진행 중이거나 성공한 주문의 수량 (INV-006: 성공은 추가 차감 없음, 실패만 한 번 반환)
SELECT '1_stock_mismatch' AS rule_violation, COUNT(*) AS violations
FROM product p
LEFT JOIN (
    SELECT oi.product_id, SUM(oi.quantity) AS reserved
    FROM order_item oi JOIN orders o ON o.id = oi.order_id
    WHERE o.status IN ('PAYMENT_PENDING', 'CONFIRMED')
    GROUP BY oi.product_id
) r ON r.product_id = p.id
WHERE p.deleted_at IS NULL AND p.stock_remaining <> 100 - COALESCE(r.reserved, 0)

UNION ALL
-- 2. 주문 상태와 결제 시도 상태가 짝이 맞지 않음 (INV-001: 한 트랜잭션에서 같이 바뀐다)
SELECT '2_status_pair_mismatch', COUNT(*)
FROM orders o JOIN payment_attempt a ON a.order_id = o.id
WHERE (o.status = 'CONFIRMED') <> (a.status = 'SUCCESS')
   OR (o.status = 'PAYMENT_FAILED') <> (a.status = 'FAILED')
   OR (o.status = 'PAYMENT_PENDING') <> (a.status IN ('REQUESTED', 'PENDING', 'UNKNOWN'))

UNION ALL
-- 3. 확정됐는데 결제액이 결제 시도 금액과 다름
SELECT '3_paid_amount_mismatch', COUNT(*)
FROM orders o JOIN payment_attempt a ON a.order_id = o.id
WHERE o.status = 'CONFIRMED' AND (o.paid_amount IS NULL OR o.paid_amount <> a.amount)

UNION ALL
-- 5. 한 주문에 결제 시도 2개 이상 (D-016)
SELECT '5_multiple_attempts', COUNT(*)
FROM (SELECT order_id FROM payment_attempt GROUP BY order_id HAVING COUNT(*) > 1) d

UNION ALL
-- 6. 포인트가 바뀐 사용자 (포인트 차감 0회)
SELECT '6_point_changed', COUNT(*)
FROM user WHERE point_balance <> 1000000;

-- 4. create 수 비교용 — 이 값이 테스트 PG가 받은 create 요청 수(pg-stub.sh count)와 같아야 한다 (INV-005)
SELECT '4_attempts_for_create_count' AS item, COUNT(*) AS value FROM payment_attempt;

-- 참고: 상태별 분포, 충돌, 수신함
SELECT o.status AS order_status, a.status AS attempt_status, COUNT(*) AS orders
FROM orders o LEFT JOIN payment_attempt a ON a.order_id = o.id
GROUP BY o.status, a.status ORDER BY o.status, a.status;

SELECT 'conflicts' AS item, COUNT(*) AS value FROM payment_conflict
UNION ALL SELECT CONCAT('inbox_', status), COUNT(*) FROM payment_callback_inbox GROUP BY status;

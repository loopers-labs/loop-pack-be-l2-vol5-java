-- 과거 포인트 결제 이관 (설계 22절)
-- 원본: 2a 이전 코드가 CONFIRMED 주문에 남긴 orders.paid_amount·payment_result. DRAFT는 결제가 없어 옮기지 않는다.
-- 재실행: 결제 기록이 이미 있는 주문은 건너뛴다. 2a 이후 확정된 주문은 confirm이 결제 기록을 만들었고 원본 열은 기본값(NOT_PAID)이라 대상이 아니다.
-- 결과 검증: 아래 두 조회가 모두 0건이어야 원본 열 삭제(별도 작업)를 진행한다.
--   select o.id from orders o left join payment_records p on p.order_id = o.id where o.status = 'CONFIRMED' and p.id is null;
--   select o.id from orders o join payment_records p on p.order_id = o.id
--     where o.payment_result = 'SUCCESS' and p.amount <> o.paid_amount;
INSERT INTO payment_records (order_id, method, amount, result)
SELECT o.id, 'POINT', o.paid_amount, 'SUCCESS'
FROM orders o
WHERE o.status = 'CONFIRMED'
  AND o.payment_result = 'SUCCESS'
  AND NOT EXISTS (SELECT 1 FROM payment_records p WHERE p.order_id = o.id);

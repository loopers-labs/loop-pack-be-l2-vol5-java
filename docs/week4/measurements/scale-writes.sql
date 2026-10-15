USE loopers_scale;
-- 신규 상품 등록 10만 건 (INSERT)
SET @t=now(6);
INSERT INTO product (id,brand_id,name,price,stock_remaining,created_at,updated_at,deleted_at)
SELECT n+1000000, (n%99)+2, concat('신규-', n), ((n*53)%100000)+100, 100, timestamp('2026-07-01')+interval n second, now(6), NULL FROM seq WHERE n<=20000;
SELECT 'insert_20k_ms', timestampdiff(microsecond,@t,now(6))/1000;
-- 재고 차감 10만 건 (주문 확정과 같은 컬럼: stock_remaining, updated_at)
SET @t=now(6);
UPDATE product SET stock_remaining=stock_remaining-1, updated_at=now(6) WHERE id BETWEEN 1000001 AND 1020000;
SELECT 'update_stock_20k_ms', timestampdiff(microsecond,@t,now(6))/1000;
-- 가격 수정 10만 건 (관리자 수정: price, name)
SET @t=now(6);
UPDATE product SET price=price+1, updated_at=now(6) WHERE id BETWEEN 1000001 AND 1020000;
SELECT 'update_price_20k_ms', timestampdiff(microsecond,@t,now(6))/1000;
-- 원복
DELETE FROM product WHERE id > 1000000;

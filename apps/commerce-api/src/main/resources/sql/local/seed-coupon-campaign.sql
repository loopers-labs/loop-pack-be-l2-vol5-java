-- 로컬 전용 쿠폰 캠페인 준비 (8주차 29절). local 프로필의 spring.sql.init이 테이블 생성 뒤 실행한다. 관리 API는 없다.
-- 같은 이름의 캠페인이 이미 있으면 넣지 않는다.
INSERT INTO coupon_campaign (name, active, total_quota, issued_count, discount_amount, created_at)
SELECT '로컬 한정 수량 쿠폰', TRUE, 100, 0, 3000, NOW(6)
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM coupon_campaign WHERE name = '로컬 한정 수량 쿠폰');

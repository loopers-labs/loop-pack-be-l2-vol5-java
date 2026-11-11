-- 로컬 전용 쿠폰 수용 설정 행 (8주차 29절). 기본 닫힘이라 --coupon.admission.open으로 연 뒤 발급할 수 있다.
-- 행이 이미 있으면 넣지 않는다.
INSERT INTO coupon_admission_settings (id, capacity, open, updated_at)
SELECT 1, 3, FALSE, NOW(6)
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM coupon_admission_settings WHERE id = 1);

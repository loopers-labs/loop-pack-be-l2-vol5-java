-- 로컬 실행용 W8 쿠폰 캠페인. 캠페인 관리 API는 만들지 않는다.
INSERT INTO coupon_campaign (name, benefit_description, active, total_quota, issued_count, end_at, created_at, updated_at)
VALUES ('W8 로컬 쿠폰 행사', '5,000원 할인권', true, 5000, 0, CURRENT_TIMESTAMP(6) + INTERVAL 1 DAY, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));

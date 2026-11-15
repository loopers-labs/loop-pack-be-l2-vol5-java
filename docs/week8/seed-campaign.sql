-- 로컬 loopers DB에만 수동 실행한다. 기존 캠페인과 사용자 데이터는 변경하지 않는다.
-- 실행할 때마다 새 캠페인이 생성되며 출력된 campaign_id를 요청에 사용한다.
INSERT INTO coupon_campaigns
    (name, active, total_quota, issued_count, benefit_description, created_at, updated_at, deleted_at)
VALUES
    ('8주차 쿠폰 실습', TRUE, 100, 0, '5,000원 할인권', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), NULL);

SELECT LAST_INSERT_ID() AS campaign_id;

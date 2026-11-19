-- 쿠폰 캠페인·발급 쿠폰 테이블 생성 (8주차 29절)
-- ddl-auto를 쓰지 않는 환경에서 쿠폰 발급 코드를 배포하기 전에 실행한다. 열·제약·인덱스 모양은
-- CouponCampaignJpaEntity·IssuedCouponJpaEntity와 같다. 테이블이 있으면 건드리지 않아 다시 실행해도 결과가 같다.
CREATE TABLE IF NOT EXISTS coupon_campaign (
    id BIGINT NOT NULL AUTO_INCREMENT,
    active BIT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    discount_amount BIGINT NOT NULL,
    issued_count INT NOT NULL DEFAULT 0,
    name VARCHAR(100) NOT NULL,
    total_quota INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_coupon_campaign_issued CHECK (issued_count >= 0 AND issued_count <= total_quota)
);

-- (campaign_id, user_id) 유일 제약이 같은 사용자의 중복 발급을 막는다. 목록은 (user_id, issued_at, id) 인덱스로 읽는다.
CREATE TABLE IF NOT EXISTS issued_coupon (
    id BIGINT NOT NULL AUTO_INCREMENT,
    campaign_id BIGINT NOT NULL,
    discount_amount BIGINT NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_issued_coupon_campaign_user UNIQUE (campaign_id, user_id),
    INDEX idx_issued_coupon_user_issued (user_id, issued_at, id)
);

-- 012 적용 뒤 신규 쿠폰 저장 구조만 추가한다. 기존 캠페인/슬롯은 보존한다.
USE loopers;

CREATE TABLE coupons (
    id BIGINT NOT NULL AUTO_INCREMENT,
    campaign_id BIGINT NOT NULL,
    slot_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    benefit_description VARCHAR(500) NOT NULL,
    discount_amount BIGINT NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_coupons_campaign_user UNIQUE (campaign_id, user_id),
    CONSTRAINT uk_coupons_slot UNIQUE (slot_id),
    INDEX idx_coupons_user_issued_id (user_id, issued_at DESC, id DESC),
    CONSTRAINT fk_coupons_slot_campaign FOREIGN KEY (slot_id, campaign_id) REFERENCES coupon_slots (id, campaign_id),
    CONSTRAINT ck_coupons_user CHECK (user_id > 0),
    CONSTRAINT ck_coupons_amount CHECK (discount_amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

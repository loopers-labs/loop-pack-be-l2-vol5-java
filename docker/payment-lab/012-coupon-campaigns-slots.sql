-- 보존 환경에서 한 번 적용한다. 기존 캠페인/슬롯을 초기화하지 않는다.
USE loopers;

CREATE TABLE coupon_campaigns (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    active BIT NOT NULL,
    total_quota INT NOT NULL,
    issued_count INT NOT NULL,
    benefit_description VARCHAR(500) NOT NULL,
    discount_amount BIGINT NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_coupon_campaign_quota CHECK (total_quota > 0 AND issued_count >= 0 AND issued_count <= total_quota),
    CONSTRAINT ck_coupon_campaign_amount CHECK (discount_amount > 0),
    CONSTRAINT ck_coupon_campaign_period CHECK (starts_at < ends_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE coupon_slots (
    id BIGINT NOT NULL AUTO_INCREMENT,
    campaign_id BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_coupon_slots_id_campaign UNIQUE (id, campaign_id),
    INDEX idx_coupon_slots_campaign_state_id (campaign_id, state, id),
    CONSTRAINT fk_coupon_slots_campaign FOREIGN KEY (campaign_id) REFERENCES coupon_campaigns (id),
    CONSTRAINT ck_coupon_slot_state CHECK (state IN ('AVAILABLE', 'ISSUED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE coupon_campaign (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    benefit VARCHAR(255) NOT NULL,
    issuable BOOLEAN NOT NULL,
    total_quota BIGINT NOT NULL,
    issued_count BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_coupon_campaign_count_within_quota
        CHECK (total_quota >= 0 AND issued_count >= 0 AND issued_count <= total_quota)
);

CREATE TABLE coupon (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    campaign_id BIGINT NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    benefit VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupon_campaign_user (campaign_id, user_id),
    KEY idx_coupon_user_issued_at_id (user_id, issued_at, id)
);

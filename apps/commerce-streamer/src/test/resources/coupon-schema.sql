-- 공유 테이블의 스키마는 commerce-api 엔티티가 만든다 (8주차 D-138). streamer 테스트는 api 없이 자기 컨테이너를 쓰므로
-- 같은 모양을 여기서 만든다 (D-172). api 엔티티(CouponCampaign · Coupon · CouponIssueRequest)를 바꾸면 함께 고친다
CREATE TABLE IF NOT EXISTS coupon_campaign (
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    deleted_at     DATETIME(6),
    name           VARCHAR(50)  NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    total_quota    INT          NOT NULL,
    issued_count   INT          NOT NULL,
    discount_type  VARCHAR(20)  NOT NULL,
    discount_value BIGINT       NOT NULL,
    description    VARCHAR(100) NOT NULL
);

CREATE TABLE IF NOT EXISTS coupon (
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    deleted_at     DATETIME(6),
    campaign_id    BIGINT       NOT NULL,
    user_id        BIGINT       NOT NULL,
    slot_no        INT          NOT NULL,
    discount_type  VARCHAR(20)  NOT NULL,
    discount_value BIGINT       NOT NULL,
    description    VARCHAR(100) NOT NULL,
    issued_at      DATETIME(6)  NOT NULL,
    CONSTRAINT uk_coupon_campaign_user UNIQUE (campaign_id, user_id),
    CONSTRAINT uk_coupon_campaign_slot UNIQUE (campaign_id, slot_no),
    INDEX idx_coupon_user_issued (user_id, issued_at, id)
);

CREATE TABLE IF NOT EXISTS coupon_issue_request (
    id          BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    deleted_at  DATETIME(6),
    campaign_id BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    sequence    BIGINT      NOT NULL,
    status      VARCHAR(20) NOT NULL,
    slot_no     INT,
    promoted_at DATETIME(6),
    coupon_id   BIGINT,
    CONSTRAINT uk_coupon_issue_request_campaign_user UNIQUE (campaign_id, user_id),
    CONSTRAINT uk_coupon_issue_request_campaign_sequence UNIQUE (campaign_id, sequence),
    INDEX idx_coupon_issue_request_status (campaign_id, status, sequence)
);

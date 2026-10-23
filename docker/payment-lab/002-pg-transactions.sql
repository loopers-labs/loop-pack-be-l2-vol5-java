-- 001 실행 후 PG 계정 또는 관리자 계정으로 명시 실행한다.
USE loopers_test_pg;
CREATE TABLE IF NOT EXISTS pg_transactions (
    transaction_key VARCHAR(64) NOT NULL PRIMARY KEY,
    provider_order_id VARCHAR(100) NOT NULL,
    amount BIGINT NOT NULL,
    callback_url VARCHAR(2048) NOT NULL,
    status VARCHAR(16) NOT NULL,
    reject_before_create BIT NOT NULL,
    final_status VARCHAR(16) NOT NULL,
    processing_delay_ms BIGINT NOT NULL,
    create_response_mode VARCHAR(16) NOT NULL,
    create_response_delay_ms BIGINT NOT NULL,
    lookup_response_mode VARCHAR(16) NOT NULL,
    lookup_response_delay_ms BIGINT NOT NULL,
    callback_mode VARCHAR(16) NOT NULL,
    callback_delay_ms BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    due_at DATETIME(6) NULL,
    INDEX idx_pg_order (provider_order_id),
    INDEX idx_pg_due (status, due_at)
);

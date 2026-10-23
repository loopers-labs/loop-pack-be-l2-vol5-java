USE loopers;
CREATE TABLE IF NOT EXISTS payment_recovery_tasks (
    attempt_key VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    state VARCHAR(24) NOT NULL,
    execution_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    used_rounds INT NOT NULL,
    last_checked_at DATETIME(6) NULL,
    last_reason VARCHAR(32) NOT NULL,
    updated_at DATETIME(6) NOT NULL
);

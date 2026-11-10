-- 주문 확정 Outbox 테이블·relay 설정 행 생성 (7주차 1·2단계)
-- ddl-auto를 쓰지 않는 환경에서 Outbox 기록·relay 코드를 배포하기 전에 실행한다. 열·제약·인덱스 모양은
-- OrderConfirmedOutboxJpaEntity·OutboxRelaySettingsJpaEntity와 같다.
-- 다시 실행해도 결과가 같고 기록된 행·사람이 바꾼 설정 값을 지우거나 되돌리지 않는다.
-- payload는 보낼 문자열을 바이트 그대로 돌려받아야 해서 JSON 타입이 아니라 TEXT로 둔다.
CREATE TABLE IF NOT EXISTS order_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(100) NOT NULL,
    order_id BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    schema_version INT NOT NULL,
    message_key VARCHAR(50) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL,
    row_failure_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    last_error VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_order_outbox_event_id UNIQUE (event_id),
    INDEX idx_order_outbox_status_next_attempt (status, next_attempt_at, id)
);

-- 1단계 스크립트로 만든 테이블에는 행 수준 실패 횟수 열이 없다. MySQL 8에는 ADD COLUMN IF NOT EXISTS가 없어
-- information_schema에서 없을 때만 추가한다. 기존 행은 0으로 채워진다.
SET @add_outbox_row_failure_count = (
    SELECT IF(COUNT(*) = 0, 'ALTER TABLE order_outbox ADD COLUMN row_failure_count INT NOT NULL DEFAULT 0', 'DO 0')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'order_outbox'
      AND column_name = 'row_failure_count'
);
PREPARE add_outbox_row_failure_count FROM @add_outbox_row_failure_count;
EXECUTE add_outbox_row_failure_count;
DEALLOCATE PREPARE add_outbox_row_failure_count;

-- relay가 회차마다 읽는 설정 행(D117). 사람이 SQL로 바꾸며, 재시작 없이 다음 회차부터 적용된다.
CREATE TABLE IF NOT EXISTS outbox_relay_settings (
    id BIGINT NOT NULL,
    batch_size INT NOT NULL,
    max_round_millis INT NOT NULL,
    enabled BIT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

-- 기본 행(묶음 100건·회차 최대 10초·켜짐)은 없을 때만 넣는다. 다시 실행해도 바뀐 값을 덮지 않는다.
INSERT INTO outbox_relay_settings (id, batch_size, max_round_millis, enabled, updated_at)
SELECT 1, 100, 10000, TRUE, NOW(6)
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM outbox_relay_settings WHERE id = 1);

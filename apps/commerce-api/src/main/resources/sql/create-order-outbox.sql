-- 주문 확정 Outbox 테이블 생성 (7주차 1단계)
-- ddl-auto를 쓰지 않는 환경에서 Outbox 기록 코드를 배포하기 전에 실행한다. 열·제약·인덱스 모양은 OrderConfirmedOutboxJpaEntity와 같다.
-- 테이블이 있으면 건드리지 않는다(IF NOT EXISTS). 다시 실행해도 결과가 같고 기록된 행을 지우지 않는다.
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
    next_attempt_at DATETIME(6) NOT NULL,
    last_error VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_order_outbox_event_id UNIQUE (event_id),
    INDEX idx_order_outbox_status_next_attempt (status, next_attempt_at, id)
);

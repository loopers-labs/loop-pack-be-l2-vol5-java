-- 환불 확인 대상 테이블 생성 (10주차 5단계, 계획 2-1)
-- ddl-auto를 쓰지 않는 환경에서 환불 추적 코드를 배포하기 전에 실행한다. 열·인덱스 모양은 PaymentRefundCaseJpaEntity와 같다.
-- 다시 실행해도 결과가 같고 기록된 행을 지우지 않는다. 만료된 PG 결제 하나가 한 행이라 order_id를 기본 키로 둔다.
CREATE TABLE IF NOT EXISTS payment_refund_cases (
    order_id BIGINT NOT NULL,
    provider_id VARCHAR(50) NOT NULL,
    provider_order_id VARCHAR(50) NOT NULL,
    amount BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    prior_provider_status VARCHAR(30) NULL,
    transaction_key VARCHAR(100) NULL,
    opened_at DATETIME(6) NOT NULL,
    handoff_at DATETIME(6) NOT NULL,
    promise_at DATETIME(6) NOT NULL,
    next_check_at DATETIME(6) NULL,
    check_attempts INT NOT NULL,
    refund_requested_at DATETIME(6) NULL,
    concluded_at DATETIME(6) NULL,
    reason VARCHAR(500) NULL,
    PRIMARY KEY (order_id),
    INDEX idx_payment_refund_cases_status_next_check (status, next_check_at)
);

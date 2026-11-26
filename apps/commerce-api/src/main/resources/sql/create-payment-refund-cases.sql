-- 환불 확인 대상 테이블 생성 (10주차 5단계, 5b 두 축 모델 계획 2·6절)
-- ddl-auto를 쓰지 않는 환경에서 환불 추적 코드를 배포하기 전에 실행한다. 열·인덱스 모양은 PaymentRefundCaseJpaEntity와 같다.
-- 다시 실행해도 결과가 같고 기록된 행을 지우지 않는다. 만료된 PG 결제 하나가 한 행이라 order_id를 기본 키로 둔다.
-- 5단계 모양(status·reason) 테이블이 이미 있으면 이 스크립트 대신 migrate-payment-refund-cases-v2.sql을 실행한다.
CREATE TABLE IF NOT EXISTS payment_refund_cases (
    order_id BIGINT NOT NULL,
    provider_id VARCHAR(50) NOT NULL,
    provider_order_id VARCHAR(50) NOT NULL,
    amount BIGINT NOT NULL,
    money_status VARCHAR(30) NOT NULL,
    refund_actor VARCHAR(20) NULL,
    owner VARCHAR(20) NOT NULL,
    owner_reason VARCHAR(500) NULL,
    owner_changed_at DATETIME(6) NULL,
    owner_closed_at DATETIME(6) NULL,
    owner_closed_by VARCHAR(100) NULL,
    prior_provider_status VARCHAR(30) NULL,
    transaction_key VARCHAR(100) NULL,
    opened_at DATETIME(6) NOT NULL,
    handoff_at DATETIME(6) NOT NULL,
    promise_at DATETIME(6) NOT NULL,
    next_check_at DATETIME(6) NULL,
    check_attempts INT NOT NULL,
    refund_requested_at DATETIME(6) NULL,
    concluded_at DATETIME(6) NULL,
    PRIMARY KEY (order_id),
    INDEX idx_payment_refund_cases_money_status_next_check (money_status, next_check_at)
);

USE loopers;
ALTER TABLE payment_attempts
    ADD COLUMN response_deadline DATETIME(6) NULL,
    ADD INDEX ix_payment_attempt_unknown_deadline (dispatch_state, outcome, response_deadline);

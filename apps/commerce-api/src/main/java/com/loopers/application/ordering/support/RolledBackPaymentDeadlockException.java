package com.loopers.application.ordering.support;

/** MySQL 데드락 근거와 전체 rollback을 함께 확인한 경우에만 Retry에 전달한다. */
public final class RolledBackPaymentDeadlockException extends PaymentApplicationFailureException {
    public RolledBackPaymentDeadlockException(RuntimeException cause) { super(cause, true); }
}

package com.loopers.application.ordering.support;

/** 업무 TX 밖으로 나온 실패와 확인한 rollback 여부를 전달한다. PG 조회 실패와 섞지 않는다. */
public class PaymentApplicationFailureException extends RuntimeException {
    private final boolean rollbackConfirmed;

    public PaymentApplicationFailureException(RuntimeException cause, boolean rollbackConfirmed) {
        super("결제 업무 반영에 실패했습니다.", cause);
        this.rollbackConfirmed = rollbackConfirmed;
    }

    public boolean isRollbackConfirmed() { return rollbackConfirmed; }
}

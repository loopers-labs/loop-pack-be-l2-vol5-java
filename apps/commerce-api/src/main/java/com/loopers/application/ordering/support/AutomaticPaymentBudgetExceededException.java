package com.loopers.application.ordering.support;

public final class AutomaticPaymentBudgetExceededException extends RuntimeException {
    public AutomaticPaymentBudgetExceededException() { super("자동 조회 시간 예산을 소진했습니다.", null, false, false); }
}

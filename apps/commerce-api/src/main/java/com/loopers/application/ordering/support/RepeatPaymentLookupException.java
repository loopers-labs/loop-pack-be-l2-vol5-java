package com.loopers.application.ordering.support;

/** 조회를 더 할 수 있는 결과만 Retry에 전달한다. DB 오류를 이 예외로 바꾸지 않는다. */
public final class RepeatPaymentLookupException extends RuntimeException {
    private final AutomaticPaymentExecution execution;

    public RepeatPaymentLookupException(AutomaticPaymentExecution execution) {
        super("기존 결제 결과를 다시 확인해야 합니다.", null, false, false);
        this.execution = execution;
    }

    public AutomaticPaymentExecution execution() { return execution; }
}

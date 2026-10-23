package com.loopers.application.ordering.result;

/** APPLIED는 성공/실패에 따른 업무 반영 완료다. PG 결제 성공만을 뜻하지 않는다. */
public record ApplyOrderPaymentResult(State state, boolean attentionRequired) {
    public enum State { APPLIED, PENDING, CONFLICT, APPLICATION_FAILED }
}

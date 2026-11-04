package com.loopers.application.ordering.result;

import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentRecoveryTask;

/** 짧은 TX에서 읽은 상태다. 다음 TX나 HTTP 시작까지 상태를 고정하는 잠금은 아니다. */
public record AutomaticPaymentState(OrderStatus orderStatus, PaymentAttempt attempt, PaymentRecoveryTask.Snapshot task) {
    public boolean completed() {
        return (orderStatus == OrderStatus.CONFIRMED || orderStatus == OrderStatus.PAYMENT_FAILED)
            && task.state() == PaymentRecoveryTask.State.APPLIED;
    }

    public boolean lookupAllowed() {
        return orderStatus == OrderStatus.PAYMENT_PENDING && task.state() != PaymentRecoveryTask.State.APPLIED
            && attempt.getDispatchState() == PaymentAttempt.DispatchState.STARTED
            && attempt.getConflict() == PaymentAttempt.Conflict.NONE
            && (attempt.getOutcome() == PaymentAttempt.Outcome.UNKNOWN || attempt.getOutcome() == PaymentAttempt.Outcome.PENDING);
    }
}

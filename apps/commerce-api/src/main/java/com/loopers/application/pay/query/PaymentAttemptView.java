package com.loopers.application.pay.query;

import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import java.time.Instant;

public record PaymentAttemptView(String key, PaymentAttempt.DispatchState dispatchState, PaymentAttempt.Outcome outcome,
                                 String transactionKey, PaymentAttempt.Rejection rejection, PaymentAttempt.Conflict conflict,
                                 PaymentRecoveryTask.State recoveryState, int usedRounds, Instant lastCheckedAt,
                                 PaymentRecoveryTask.Reason lastReason) {
    public static PaymentAttemptView from(PaymentAttempt attempt, PaymentRecoveryTask task) {
        return new PaymentAttemptView(attempt.getAttemptKey(), attempt.getDispatchState(), attempt.getOutcome(),
            attempt.getTransactionKey(), attempt.getRejection(), attempt.getConflict(), task.getState(), task.getUsedRounds(),
            task.getLastCheckedAt(), task.getLastReason());
    }
}

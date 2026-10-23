package com.loopers.application.ordering.result;

import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentRecoveryTask;

public record PaymentCallbackContext(PaymentAttempt attempt, long ownerUserId,
                                     PaymentRecoveryTask.Snapshot expectedExecution) {}

package com.loopers.application.pay.usecase;

import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentObservation;

public interface RecordPaymentObservationUseCase {
    PaymentAttempt execute(String attemptKey, PaymentObservation observation);
}

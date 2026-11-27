package com.loopers.application.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

public final class PaymentAttemptKeyConflictException extends CoreException {
    public PaymentAttemptKeyConflictException() {
        super(ErrorType.CONFLICT);
    }
}

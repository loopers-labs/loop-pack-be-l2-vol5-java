package com.loopers.application.payment;

import com.loopers.domain.payment.Payment;

public sealed interface PaymentBegin {

    Payment payment();

    record Granted(Payment payment) implements PaymentBegin {
    }

    record Repeated(Payment payment) implements PaymentBegin {
    }
}

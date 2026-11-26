package com.loopers.application.payment;

import com.loopers.domain.payment.PaymentStatus;

import java.time.Duration;

record PaymentTransitioned(
    Long paymentId,
    Long orderId,
    String providerOrderId,
    PaymentStatus from,
    PaymentStatus to,
    boolean settled,
    Duration sinceRequested,
    PaymentTransactions.Origin origin
) {

    boolean changed() {
        return from != to;
    }
}

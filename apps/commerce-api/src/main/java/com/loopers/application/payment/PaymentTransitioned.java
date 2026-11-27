package com.loopers.application.payment;

import com.loopers.domain.payment.PaymentDiscrepancy;
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
    PaymentDiscrepancy.Reason discrepancy,
    PaymentTransactions.Origin origin
) {

    boolean changed() {
        return from != to;
    }

    boolean resolvesUnknownCreateOrConflicts() {
        if (!changed()) {
            return false;
        }
        boolean createLeftUnapplied = from == PaymentStatus.REQUESTED && origin != PaymentTransactions.Origin.CONFIRM;
        return from == PaymentStatus.UNKNOWN || createLeftUnapplied || to == PaymentStatus.CONFLICT;
    }
}

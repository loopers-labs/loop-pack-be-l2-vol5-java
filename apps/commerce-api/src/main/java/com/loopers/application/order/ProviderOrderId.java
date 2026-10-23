package com.loopers.application.order;

import com.loopers.domain.payment.PaymentAttempt;

import java.util.Locale;

/** Server-owned mapping. Call only after ownership checks; existing attempts retain their stored mapping. */
public final class ProviderOrderId {
    private ProviderOrderId() {
    }

    public static String resolve(long orderId, PaymentAttempt storedAttempt) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Provider order mapping requires a positive order identity");
        }
        if (storedAttempt != null) {
            if (storedAttempt.getOrderId() != orderId) {
                throw new IllegalStateException("Stored payment attempt belongs to another order");
            }
            return storedAttempt.getProviderOrderId();
        }
        return String.format(Locale.ROOT, "LP-ORD-%010d", orderId);
    }
}

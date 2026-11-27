package com.loopers.application.payment;

import com.loopers.domain.payment.Payment;
import org.slf4j.MDC;

import java.util.function.Supplier;

final class PaymentLogContext {
    private PaymentLogContext() {}

    static <T> T withPayment(Payment payment, Supplier<T> action) {
        String previousOrderId = MDC.get("orderId");
        String previousAttemptKey = MDC.get("paymentAttemptKey");
        MDC.put("orderId", Long.toString(payment.orderId()));
        MDC.put("paymentAttemptKey", payment.paymentAttemptKey().toString());
        try {
            return action.get();
        } finally {
            restore("paymentAttemptKey", previousAttemptKey);
            restore("orderId", previousOrderId);
        }
    }

    private static void restore(String key, String previous) {
        if (previous == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previous);
        }
    }
}

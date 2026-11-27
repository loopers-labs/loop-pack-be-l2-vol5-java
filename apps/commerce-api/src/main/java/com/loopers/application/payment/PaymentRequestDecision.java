package com.loopers.application.payment;

import lombok.extern.slf4j.Slf4j;

import java.util.Locale;
import java.util.UUID;

@Slf4j
enum PaymentRequestDecision {
    SEND_TO_PG, REUSE_EXISTING, ATTEMPT_KEY_CONFLICT;

    void record(long orderId, UUID requestedKey) {
        String decision = name().toLowerCase(Locale.ROOT);
        log.atInfo()
                .addKeyValue("step", "payment_request")
                .addKeyValue("decision", decision)
                .addKeyValue("orderId", Long.toString(orderId))
                .addKeyValue("paymentAttemptKey", requestedKey.toString())
                .log("Payment request {} for order {}", decision, orderId);
    }
}

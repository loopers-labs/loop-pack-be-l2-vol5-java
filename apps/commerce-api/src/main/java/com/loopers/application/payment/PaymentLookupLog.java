package com.loopers.application.payment;

import com.loopers.domain.payment.Payment;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;

@Slf4j
final class PaymentLookupLog {
    private PaymentLookupLog() {}

    static void unavailable(Payment payment, String lookupBy, RuntimeException failure) {
        switch (failure) {
            case PaymentGateway.LookupBlockedException blocked ->
                    warnUnavailable(payment, lookupBy, "circuit_blocked", null);
            case PaymentGateway.UnconfirmedException unconfirmed ->
                    warnUnavailable(payment, lookupBy, unconfirmed.reason().name().toLowerCase(Locale.ROOT), unconfirmed.pgHttpStatus());
            default -> throw failure;
        }
    }

    private static void warnUnavailable(Payment payment, String lookupBy, String reason, Integer pgHttpStatus) {
        var entry = log.atWarn().addKeyValue("step", "pg_lookup").addKeyValue("outcome", "unavailable")
                .addKeyValue("providerOrderId", payment.providerOrderId()).addKeyValue("lookupBy", lookupBy)
                .addKeyValue("reason", reason);
        if (pgHttpStatus != null) {
            entry.addKeyValue("pgHttpStatus", pgHttpStatus);
        }
        entry.log("step=pg_lookup orderId={} providerOrderId={} outcome=unavailable reason={}{}",
                payment.orderId(), payment.providerOrderId(), reason, pgHttpStatus == null ? "" : " pgHttpStatus=" + pgHttpStatus);
    }
}

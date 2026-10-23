package com.loopers.application.payment;

import com.loopers.domain.payment.CreateObservation;
import com.loopers.domain.payment.LookupResult;
import com.loopers.domain.payment.PaymentDiscrepancy;
import com.loopers.domain.payment.PaymentStatus;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
@RequiredArgsConstructor
public class PaymentMetrics {

    private final MeterRegistry meterRegistry;

    void created(CreateObservation observation) {
        String result = switch (observation) {
            case CreateObservation.Accepted accepted -> "accepted";
            case CreateObservation.RejectedNoEffect rejected -> "rejected";
            case CreateObservation.NotSent notSent -> "not_sent";
            case CreateObservation.CreateUnknown unknown -> "unknown";
        };
        meterRegistry.counter("payment.create", "result", result).increment();
    }

    void lookedUp(String source, LookupResult result) {
        String outcome = switch (result) {
            case LookupResult.Found found -> "found";
            case LookupResult.NotFound notFound -> "not_found";
            case LookupResult.Unavailable unavailable -> "unavailable";
        };
        meterRegistry.counter("payment.lookup", "source", source, "result", outcome).increment();
    }

    void observed(PaymentStatus before, PaymentStatus after) {
        String to = before == after ? "noop" : after.name().toLowerCase(Locale.ROOT);
        meterRegistry.counter("payment.observe", "to", to).increment();
    }

    void discrepancy(PaymentDiscrepancy.Reason reason) {
        meterRegistry.counter("payment.discrepancy", "reason", reason.name().toLowerCase(Locale.ROOT)).increment();
    }

    void bulkheadRejected() {
        meterRegistry.counter("payment.bulkhead.rejected").increment();
    }

    void callback(PaymentFacade.CallbackResult result) {
        meterRegistry.counter("payment.callback", "result", result.name().toLowerCase(Locale.ROOT)).increment();
    }
}

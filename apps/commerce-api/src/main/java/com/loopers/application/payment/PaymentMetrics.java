package com.loopers.application.payment;

import com.loopers.domain.payment.CreateObservation;
import com.loopers.domain.payment.LookupResult;
import com.loopers.domain.payment.PaymentDiscrepancy;
import com.loopers.domain.payment.PaymentStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

@Component
public class PaymentMetrics {

    private static final Duration[] SETTLE_BUCKETS = {
        Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(10),
        Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(300)
    };

    private final MeterRegistry meterRegistry;

    public PaymentMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        CreateResult.names().forEach(result -> meterRegistry.counter("payment.create", "result", result));
    }

    void created(CreateObservation observation) {
        meterRegistry.counter("payment.create", "result", CreateResult.nameOf(observation)).increment();
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
        String from = before.name().toLowerCase(Locale.ROOT);
        String to = before == after ? "noop" : after.name().toLowerCase(Locale.ROOT);
        meterRegistry.counter("payment.observe", "from", from, "to", to).increment();
    }

    void settled(PaymentStatus outcome, Duration sinceRequested) {
        Timer.builder("payment.settle")
            .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
            .serviceLevelObjectives(SETTLE_BUCKETS)
            .register(meterRegistry)
            .record(sinceRequested);
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

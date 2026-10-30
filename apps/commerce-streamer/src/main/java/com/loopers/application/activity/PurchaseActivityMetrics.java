package com.loopers.application.activity;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class PurchaseActivityMetrics {

    private static final String NO_REASON = "none";

    private final MeterRegistry meterRegistry;

    void recorded(Instant confirmedAt) {
        handled(PurchaseOutcome.RECORDED);
        meterRegistry.timer("purchase.activity.delay").record(Duration.between(confirmedAt, Instant.now()));
    }

    void handled(PurchaseOutcome outcome) {
        handled(outcome, NO_REASON);
    }

    void handled(PurchaseOutcome outcome, String reason) {
        meterRegistry.counter("purchase.activity",
            "outcome", outcome.name().toLowerCase(Locale.ROOT), "reason", reason).increment();
    }
}

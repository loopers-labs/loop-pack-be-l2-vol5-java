package com.loopers.domains.order.in;

import com.loopers.domains.order.app.port.UnresolvedPaymentCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class UnresolvedPaymentMetrics {
    private final UnresolvedPaymentCounter unresolvedPaymentCounter;
    private final MeterRegistry meterRegistry;

    public UnresolvedPaymentMetrics(UnresolvedPaymentCounter unresolvedPaymentCounter, MeterRegistry meterRegistry) {
        this.unresolvedPaymentCounter = unresolvedPaymentCounter;
        this.meterRegistry = meterRegistry;
    }

    public void refresh() {
    }
}

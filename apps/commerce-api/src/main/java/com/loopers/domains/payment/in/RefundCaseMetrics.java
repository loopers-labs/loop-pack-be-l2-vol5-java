package com.loopers.domains.payment.in;

import com.loopers.domains.payment.app.port.RefundCaseCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class RefundCaseMetrics {
    static final String METRIC_NAME = "commerce.payment.refund_cases";

    private final RefundCaseCounter refundCaseCounter;

    public RefundCaseMetrics(RefundCaseCounter refundCaseCounter, MeterRegistry meterRegistry) {
        this.refundCaseCounter = refundCaseCounter;
    }

    public void refresh() {
    }
}

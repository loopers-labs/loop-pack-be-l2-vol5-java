package com.loopers.domains.payment.out;

import com.loopers.domains.payment.app.port.RefundOutcomeRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class MicrometerRefundOutcomeRecorder implements RefundOutcomeRecorder {
    static final String METRIC_NAME = "commerce.payment.refund_outcome";

    public MicrometerRefundOutcomeRecorder(MeterRegistry meterRegistry) {
    }

    @Override
    public void record(Outcome outcome) {
    }
}

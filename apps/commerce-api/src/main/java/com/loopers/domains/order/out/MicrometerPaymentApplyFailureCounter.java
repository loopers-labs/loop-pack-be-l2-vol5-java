package com.loopers.domains.order.out;

import com.loopers.domains.order.app.port.PaymentApplyFailureCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class MicrometerPaymentApplyFailureCounter implements PaymentApplyFailureCounter {
    static final String METRIC_NAME = "commerce.payment.apply_failed";

    public MicrometerPaymentApplyFailureCounter(MeterRegistry meterRegistry) {
    }

    @Override
    public void record(Source source) {
    }
}

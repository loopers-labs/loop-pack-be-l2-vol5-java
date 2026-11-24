package com.loopers.domains.order.out;

import com.loopers.domains.order.app.port.PaymentOutcomeRecorder;
import org.springframework.stereotype.Component;

@Component
public class MicrometerPaymentOutcomeRecorder implements PaymentOutcomeRecorder {
    @Override
    public void record(Outcome outcome) {
    }
}

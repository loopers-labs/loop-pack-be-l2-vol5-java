package com.loopers.application.payment;

import org.springframework.stereotype.Component;

@Component
public class NoOpPaymentTransactionProbe implements PaymentTransactionProbe {

    @Override
    public void reach(Point point, String operation, Long orderId) {
    }
}

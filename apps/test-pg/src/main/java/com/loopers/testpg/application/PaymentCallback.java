package com.loopers.testpg.application;

import com.loopers.testpg.domain.Payment;

public interface PaymentCallback {
    void send(Payment payment);
}

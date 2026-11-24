package com.loopers.domains.order.app.port;

public interface PaymentOutcomeRecorder {
    void record(Outcome outcome);

    enum Outcome {
        CONFIRMED, FAILED, EXPIRED
    }
}

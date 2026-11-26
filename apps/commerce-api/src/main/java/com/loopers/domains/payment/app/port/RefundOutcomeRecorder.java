package com.loopers.domains.payment.app.port;

public interface RefundOutcomeRecorder {
    void record(Outcome outcome);

    enum Outcome {
        REFUNDED, CLOSED_NO_CHARGE, HANDED_TO_HUMAN
    }
}

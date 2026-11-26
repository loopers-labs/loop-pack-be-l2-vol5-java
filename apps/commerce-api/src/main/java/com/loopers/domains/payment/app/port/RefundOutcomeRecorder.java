package com.loopers.domains.payment.app.port;

import com.loopers.domains.payment.domain.RefundCase;

// 골격(w10s5b S7 Red): 새 두 메서드는 Green에서 추상 메서드로 바꾸고 옛 record(Outcome)는 지운다.
public interface RefundOutcomeRecorder {
    default void record(Outcome outcome) {
    }

    default void recordOutcome(Result result, RefundCase.RefundActor actor) {
    }

    default void recordPersonAssigned(String reason) {
    }

    enum Outcome {
        REFUNDED, CLOSED_NO_CHARGE, HANDED_TO_HUMAN
    }

    enum Result {
        REFUNDED, NO_CHARGE
    }
}

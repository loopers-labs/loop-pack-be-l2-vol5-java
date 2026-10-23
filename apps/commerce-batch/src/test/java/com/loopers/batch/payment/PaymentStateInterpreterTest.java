package com.loopers.batch.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentStateInterpreterTest {
    private final PaymentStateInterpreter interpreter = new PaymentStateInterpreter();

    @Test
    @DisplayName("Toss의 최종 상태를 내부 결제 상태로 해석한다")
    void interpretsTossTerminalStates() {
        assertThat(interpret("DONE")).isEqualTo(RemotePaymentState.SUCCESS);
        assertThat(interpret("ABORTED")).isEqualTo(RemotePaymentState.FAILED);
        assertThat(interpret("EXPIRED")).isEqualTo(RemotePaymentState.FAILED);
        assertThat(interpret("CANCELED")).isEqualTo(RemotePaymentState.CANCELED);
        assertThat(interpret("PARTIAL_CANCELED")).isEqualTo(RemotePaymentState.PARTIALLY_CANCELED);
    }

    @Test
    @DisplayName("진행 중이거나 조회에 실패한 응답은 최종 상태로 해석하지 않는다")
    void keepsUnresolvedStatesUnresolved() {
        assertThat(interpret("IN_PROGRESS")).isEqualTo(RemotePaymentState.PROCESSING);
        assertThat(interpreter.interpret(new PaymentLookupClient.LookupResult(500, null, "{}")))
            .isEqualTo(RemotePaymentState.UNKNOWN);
        assertThat(interpreter.interpret(new PaymentLookupClient.LookupResult(200, null, "{}")))
            .isEqualTo(RemotePaymentState.UNKNOWN);
    }

    private RemotePaymentState interpret(String tossStatus) {
        return interpreter.interpret(new PaymentLookupClient.LookupResult(200, tossStatus, "{}"));
    }
}

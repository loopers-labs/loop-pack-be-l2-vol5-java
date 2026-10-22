package com.loopers.domain.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentAttemptModelTest {

    private PaymentAttemptModel newAttempt() {
        return new PaymentAttemptModel(1L, "key-001", "LP-ORD-0000000001", 10_000L);
    }

    @DisplayName("생성 직후 상태는 PENDING이다.")
    @Test
    void initialStatus_isPending() {
        assertThat(newAttempt().getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
    }

    @DisplayName("success() 호출 시 transactionKey가 설정되고 SUCCESS가 된다.")
    @Test
    void success_setsTransactionKeyAndStatus() {
        PaymentAttemptModel attempt = newAttempt();

        attempt.success("TX-001");

        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
        assertThat(attempt.getTransactionKey()).isEqualTo("TX-001");
        assertThat(attempt.isTerminal()).isTrue();
    }

    @DisplayName("failed() 호출 시 FAILED가 되고 종결 상태다.")
    @Test
    void failed_setsFailedStatus() {
        PaymentAttemptModel attempt = newAttempt();

        attempt.failed();

        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(attempt.isTerminal()).isTrue();
    }

    @DisplayName("rejectedNoEffect() 호출 시 REJECTED_NO_EFFECT가 되고 종결 상태다.")
    @Test
    void rejectedNoEffect_setsRejectedStatus() {
        PaymentAttemptModel attempt = newAttempt();

        attempt.rejectedNoEffect();

        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.REJECTED_NO_EFFECT);
        assertThat(attempt.isTerminal()).isTrue();
    }

    @DisplayName("종결된 상태에서 success()를 호출하면 CONFLICT 예외가 발생한다.")
    @Test
    void success_throwsOnTerminalStatus() {
        PaymentAttemptModel attempt = newAttempt();
        attempt.success("TX-001");

        assertThatThrownBy(() -> attempt.success("TX-002"))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
    }

    @DisplayName("unknown() 호출 시 UNKNOWN이 되고 비종결 상태다.")
    @Test
    void unknown_setsUnknownStatus_andIsNotTerminal() {
        PaymentAttemptModel attempt = newAttempt();

        attempt.unknown();

        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(attempt.isTerminal()).isFalse();
    }
}

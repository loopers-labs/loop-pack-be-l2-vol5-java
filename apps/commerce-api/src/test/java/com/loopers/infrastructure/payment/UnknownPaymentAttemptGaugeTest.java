package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttemptStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UnknownPaymentAttemptGaugeTest {
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void failedCountIsUnavailableUntilTheNextSuccessfulRefresh(CapturedOutput output) {
        var attempts = mock(PaymentAttemptJpaRepository.class);
        when(attempts.countByStatus(PaymentAttemptStatus.UNKNOWN)).thenReturn(1L)
            .thenThrow(new DataAccessResourceFailureException("DB unavailable")).thenReturn(0L);

        var registry = new SimpleMeterRegistry();
        try {
            var sampler = new UnknownPaymentAttemptGauge(attempts, registry);
            var gauge = registry.get("commerce.payment.attempts.unknown").gauge();
            assertThat(gauge.value()).isNaN();
            sampler.refresh();
            assertThat(gauge.value()).isOne();
            // 집계 실패는 다시 던지지 않는다. 다시 던지면 스케줄러가 주기마다 스택트레이스를 ERROR로 남긴다.
            sampler.refresh();
            assertThat(gauge.value()).isNaN();
            assertThat(output).contains("UNKNOWN 결제 시도 집계 실패: error=DataAccessResourceFailureException");
            assertThat(output).doesNotContain("at com.loopers");
            sampler.refresh();
            assertThat(gauge.value()).isZero();
        } finally {
            registry.close();
        }
    }
}

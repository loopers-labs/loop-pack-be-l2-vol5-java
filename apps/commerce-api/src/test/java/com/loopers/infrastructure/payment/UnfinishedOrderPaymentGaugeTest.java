package com.loopers.infrastructure.payment;

import com.loopers.domain.order.OrderStatus;
import com.loopers.support.TestClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UnfinishedOrderPaymentGaugeTest {
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void elapsedSinceEarliestPaymentStartIsFixedAtEachRefreshAndFailureIsUnavailable(CapturedOutput output) {
        var attempts = mock(PaymentAttemptJpaRepository.class);
        when(attempts.findEarliestPaymentStartByOrderStatus(OrderStatus.PAYMENT_PENDING))
            .thenReturn(Optional.of(Instant.EPOCH.atZone(ZoneOffset.UTC)))
            .thenReturn(Optional.of(Instant.EPOCH.atZone(ZoneOffset.UTC)))
            .thenThrow(new DataAccessResourceFailureException("DB unavailable"))
            .thenReturn(Optional.empty());
        var clock = new TestClock();
        clock.advance(Duration.ofSeconds(30));
        var registry = new SimpleMeterRegistry();
        try {
            var sampler = new UnfinishedOrderPaymentGauge(attempts, registry, clock);
            var gauge = registry.get("commerce.payment.orders.unfinished.oldest").gauge();
            assertThat(gauge.value()).isNaN();

            sampler.refresh();
            assertThat(gauge.value()).isEqualTo(30.0);
            // 다음 집계 전에는 값이 그대로다. 그 사이 주문이 끝났어도 경과 시간이 늘어나지 않는다.
            clock.advance(Duration.ofSeconds(5));
            assertThat(gauge.value()).isEqualTo(30.0);
            sampler.refresh();
            assertThat(gauge.value()).isEqualTo(35.0);

            sampler.refresh();
            assertThat(gauge.value()).isNaN();
            assertThat(output).contains("미완료 주문 결제 시작 시각 집계 실패: error=DataAccessResourceFailureException");
            assertThat(output).doesNotContain("at com.loopers");

            sampler.refresh();
            assertThat(gauge.value()).isZero();
        } finally {
            registry.close();
        }
    }
}

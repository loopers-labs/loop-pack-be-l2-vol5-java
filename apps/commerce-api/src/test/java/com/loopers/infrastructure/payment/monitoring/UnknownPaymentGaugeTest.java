package com.loopers.infrastructure.payment.monitoring;

import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UnknownPaymentGaugeTest {
    @Test
    void defaultConfigurationExposesOneGaugeWithoutPerOrderTags() {
        new ApplicationContextRunner().withUserConfiguration(PaymentObservationConfiguration.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(PaymentAttemptJpaRepository.class, () -> mock(PaymentAttemptJpaRepository.class))
            .run(context -> {
                var gauge = context.getBean(MeterRegistry.class).find("commerce.payment.unknown").gauge();
                assertThat(gauge).isNotNull();
                assertThat(gauge.getId().getTags()).isEmpty();
            });
    }

    @Test
    void scrapesReadTheCachedUnknownCountWithoutCallingTheRepository() {
        var attempts = mock(PaymentAttemptJpaRepository.class);
        var registry = new SimpleMeterRegistry();
        try {
            var sampler = new UnknownPaymentGauge(attempts, registry, Duration.ofSeconds(5));
            Gauge gauge = registry.get("commerce.payment.unknown").gauge();
            assertThat(gauge.value()).isNaN();
            verifyNoInteractions(attempts);

            when(attempts.countByStatus(PaymentAttemptStatus.UNKNOWN)).thenReturn(3L, 0L);
            sampler.refresh();
            assertThat(gauge.value()).isEqualTo(3);
            assertThat(gauge.value()).isEqualTo(3);
            verify(attempts).countByStatus(PaymentAttemptStatus.UNKNOWN);

            sampler.refresh();
            assertThat(gauge.value()).isZero();
            verify(attempts, times(2)).countByStatus(PaymentAttemptStatus.UNKNOWN);
        } finally {
            registry.close();
        }
    }

    @Test
    void failedReadInvalidatesTheSampleImmediatelyAndNextSuccessfulReadRestoresIt() {
        var attempts = mock(PaymentAttemptJpaRepository.class);
        when(attempts.countByStatus(PaymentAttemptStatus.UNKNOWN)).thenReturn(2L)
            .thenThrow(new DataAccessResourceFailureException("Test database unavailable")).thenReturn(1L);
        var registry = new SimpleMeterRegistry();
        try {
            var sampler = new UnknownPaymentGauge(attempts, registry, Duration.ofSeconds(5));
            var gauge = registry.get("commerce.payment.unknown").gauge();

            sampler.refresh();
            assertThat(gauge.value()).isEqualTo(2);
            sampler.refresh();
            assertThat(gauge.value()).isNaN();
            sampler.refresh();
            assertThat(gauge.value()).isEqualTo(1);
        } finally {
            registry.close();
        }
    }

    @Test
    void anOverdueSampleExpiresEvenWhenTheNextDbReadHasNotCompleted() {
        var attempts = mock(PaymentAttemptJpaRepository.class);
        when(attempts.countByStatus(PaymentAttemptStatus.UNKNOWN)).thenReturn(0L, 4L);
        var clock = new MockClock();
        var registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
        try {
            var sampler = new UnknownPaymentGauge(attempts, registry, Duration.ofSeconds(5));
            var gauge = registry.get("commerce.payment.unknown").gauge();

            sampler.refresh();
            clock.add(Duration.ofMillis(9999));
            assertThat(gauge.value()).isZero();
            clock.add(Duration.ofMillis(1));
            assertThat(gauge.value()).isNaN();
            verify(attempts).countByStatus(PaymentAttemptStatus.UNKNOWN);
            sampler.refresh();
            assertThat(gauge.value()).isEqualTo(4);
        } finally {
            registry.close();
        }
    }
}

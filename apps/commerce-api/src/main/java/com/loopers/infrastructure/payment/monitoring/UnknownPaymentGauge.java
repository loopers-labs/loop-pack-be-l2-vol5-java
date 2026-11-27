package com.loopers.infrastructure.payment.monitoring;

import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;

final class UnknownPaymentGauge {
    static final String REFRESH_INTERVAL = "${commerce.payment-observation.refresh-interval:5s}";
    private static final Logger log = LoggerFactory.getLogger(UnknownPaymentGauge.class);
    private final PaymentAttemptJpaRepository attempts;
    private final Clock clock;
    private final long maxAgeNanos;
    private volatile Sample sample;

    UnknownPaymentGauge(PaymentAttemptJpaRepository attempts, MeterRegistry registry, Duration interval) {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("Payment observation refresh interval must be positive");
        }
        this.attempts = attempts;
        this.clock = registry.config().clock();
        this.maxAgeNanos = interval.multipliedBy(2).toNanos();
        Gauge.builder("commerce.payment.unknown", this, UnknownPaymentGauge::value)
            .description("Current DB count of UNKNOWN payment attempts; NaN when unavailable or stale")
            .register(registry);
    }

    @Scheduled(fixedDelayString = REFRESH_INTERVAL, scheduler = "paymentObservationScheduler")
    public void refresh() {
        long started = clock.monotonicTime();
        try {
            sample = new Sample(attempts.countByStatus(PaymentAttemptStatus.UNKNOWN), started);
        } catch (RuntimeException unavailable) {
            sample = null;
            log.warn("Unknown payment count unavailable errorType={}", unavailable.getClass().getSimpleName());
        }
    }

    private double value() {
        Sample current = sample;
        if (current == null || clock.monotonicTime() - current.startedNanos() >= maxAgeNanos) {
            return Double.NaN;
        }
        return current.count();
    }

    private record Sample(long count, long startedNanos) { }
}

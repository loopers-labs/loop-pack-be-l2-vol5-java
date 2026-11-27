package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttemptStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.autoconfigure.metrics.export.ConditionalOnEnabledMetricsExport;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnEnabledMetricsExport("prometheus")
class UnknownPaymentAttemptGauge {
    private final PaymentAttemptJpaRepository attempts;
    private volatile double count = Double.NaN;

    UnknownPaymentAttemptGauge(PaymentAttemptJpaRepository attempts, MeterRegistry registry) {
        this.attempts = attempts;
        Gauge.builder("commerce.payment.attempts.unknown", this, gauge -> gauge.count)
            .description("Current number of payment attempts with database status UNKNOWN")
            .register(registry);
    }

    @Scheduled(fixedDelayString = "${commerce.payment.unknown-count.refresh-delay:5s}")
    void refresh() {
        try {
            count = attempts.countByStatus(PaymentAttemptStatus.UNKNOWN);
        } catch (RuntimeException exception) {
            // 다시 던지면 DB가 안 되는 동안 스케줄러가 주기마다 스택트레이스를 ERROR로 남긴다. NaN으로 집계 불가를 알린다.
            count = Double.NaN;
            log.warn("UNKNOWN 결제 시도 집계 실패: error={}", exception.getClass().getSimpleName());
        }
    }
}

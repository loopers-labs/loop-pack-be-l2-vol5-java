package com.loopers.infrastructure.payment;

import com.loopers.domain.order.OrderStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.metrics.export.ConditionalOnEnabledMetricsExport;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * 결제를 시작한 뒤 아직 끝나지 않은(PAYMENT_PENDING) 주문 중 가장 오래된 주문의 경과 시간.
 * 결제 시도 상태와 관계없이 주문 기준으로 센다. 결제 시도가 SUCCESS인데 주문 반영(TX C)만 실패한 주문도 여기 잡힌다.
 * 결제 시도 생성 시각은 시스템 시계로 저장되므로 경과 시간도 시스템 시계로 잰다. 테스트용 고정 Clock 빈과 섞지 않는다.
 * 경과 시간은 집계한 순간에 계산해 저장한다. 읽을 때마다 다시 계산하면 이미 끝난 주문의 시작 시각이 다음 집계까지
 * 계속 늘어나, 정상 흐름(최대 2.2초)이 6.6초까지 보였다(10주차 implementation-log K).
 */
@Slf4j
@Component
@ConditionalOnEnabledMetricsExport("prometheus")
class UnfinishedOrderPaymentGauge {
    private final PaymentAttemptJpaRepository attempts;
    private final Clock clock;
    private volatile double oldestSeconds = Double.NaN;

    @Autowired
    UnfinishedOrderPaymentGauge(PaymentAttemptJpaRepository attempts, MeterRegistry registry) {
        this(attempts, registry, Clock.systemUTC());
    }

    UnfinishedOrderPaymentGauge(PaymentAttemptJpaRepository attempts, MeterRegistry registry, Clock clock) {
        this.attempts = attempts;
        this.clock = clock;
        Gauge.builder("commerce.payment.orders.unfinished.oldest", this, gauge -> gauge.oldestSeconds)
            .baseUnit("seconds")
            .description("Seconds since the earliest payment start among orders still in PAYMENT_PENDING (0 when none)")
            .register(registry);
    }

    @Scheduled(fixedDelayString = "${commerce.payment.unfinished-order.refresh-delay:5s}")
    void refresh() {
        try {
            Optional<ZonedDateTime> earliest = attempts.findEarliestPaymentStartByOrderStatus(OrderStatus.PAYMENT_PENDING);
            oldestSeconds = earliest
                .map(start -> Duration.between(start.toInstant(), clock.instant()).toMillis() / 1000.0)
                .orElse(0.0);
        } catch (RuntimeException exception) {
            // UNKNOWN 집계와 같은 이유로 다시 던지지 않고 NaN으로 집계 불가를 알린다.
            oldestSeconds = Double.NaN;
            log.warn("미완료 주문 결제 시작 시각 집계 실패: error={}", exception.getClass().getSimpleName());
        }
    }
}

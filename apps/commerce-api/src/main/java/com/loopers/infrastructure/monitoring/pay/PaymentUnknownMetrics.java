package com.loopers.infrastructure.monitoring.pay;

import com.loopers.application.pay.query.PaymentMetricQueryDao;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Instant;
import java.util.OptionalLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentUnknownMetrics implements MeterBinder {
    private final PaymentMetricQueryDao payments;
    private final Clock clock;
    private volatile Snapshot latest = new Snapshot(Double.NaN, Double.NaN);

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("commerce.payment.unknown", this, metrics -> metrics.latest.count())
            .description("발송한 PG 결제 중 응답 기한을 넘겼고 결과를 모르는 주문 수")
            .register(registry);
        Gauge.builder("commerce.payment.unknown.last.success.timestamp.seconds", this, metrics -> metrics.latest.lastSuccessSeconds())
            .description("미확인 주문 수를 마지막으로 판정한 관찰 시각 (Unix seconds)")
            .register(registry);
    }

    public synchronized void refresh() {
        Instant observedAt = clock.instant();
        OptionalLong count;
        try {
            count = payments.countOverdueUnknown(observedAt);
        } catch (RuntimeException failure) {
            log.warn("미확인 주문 집계에 실패했습니다. observedAt={}", observedAt, failure);
            count = OptionalLong.empty();
        }
        latest = count.isPresent()
            ? new Snapshot(count.getAsLong(), observedAt.toEpochMilli() / 1000.0)
            : new Snapshot(Double.NaN, latest.lastSuccessSeconds());
    }

    private record Snapshot(double count, double lastSuccessSeconds) {}
}

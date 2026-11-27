package com.loopers.infrastructure.monitoring.pay;

import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "payment.metrics.refresh-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class PaymentUnknownMetricsScheduler {
    private final PaymentUnknownMetrics metrics;

    @Scheduled(fixedDelay = 15, timeUnit = TimeUnit.SECONDS)
    public void refresh() {
        metrics.refresh();
    }
}

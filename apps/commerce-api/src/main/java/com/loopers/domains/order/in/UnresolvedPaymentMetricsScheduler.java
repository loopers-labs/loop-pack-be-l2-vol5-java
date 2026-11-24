package com.loopers.domains.order.in;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "commerce.payment.unresolved-metric.enabled", havingValue = "true")
public class UnresolvedPaymentMetricsScheduler {
    private final UnresolvedPaymentMetrics unresolvedPaymentMetrics;

    public UnresolvedPaymentMetricsScheduler(UnresolvedPaymentMetrics unresolvedPaymentMetrics) {
        this.unresolvedPaymentMetrics = unresolvedPaymentMetrics;
    }

    @Scheduled(fixedDelayString = "${commerce.payment.unresolved-metric.refresh-interval}")
    public void refresh() {
    }
}

package com.loopers.domains.order.in;

import com.loopers.domains.payment.in.RefundCaseMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "commerce.payment.unresolved-metric.enabled", havingValue = "true")
public class UnresolvedPaymentMetricsScheduler {
    public static final String TASK_SCHEDULER = "unresolvedMetricTaskScheduler";

    private final UnresolvedPaymentMetrics unresolvedPaymentMetrics;
    private final RefundCaseMetrics refundCaseMetrics;

    public UnresolvedPaymentMetricsScheduler(UnresolvedPaymentMetrics unresolvedPaymentMetrics,
        RefundCaseMetrics refundCaseMetrics) {
        this.unresolvedPaymentMetrics = unresolvedPaymentMetrics;
        this.refundCaseMetrics = refundCaseMetrics;
    }

    @Scheduled(fixedDelayString = "${commerce.payment.unresolved-metric.refresh-interval}", scheduler = TASK_SCHEDULER)
    public void refresh() {
        unresolvedPaymentMetrics.refresh();
    }
}

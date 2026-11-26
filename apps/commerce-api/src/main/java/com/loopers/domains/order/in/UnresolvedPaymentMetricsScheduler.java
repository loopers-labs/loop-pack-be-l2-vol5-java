package com.loopers.domains.order.in;

import com.loopers.domains.payment.in.RefundCaseMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 환불 확인 대상 gauge도 지표 갱신의 몫이라 같은 작업에서 갱신한다(10주차 5단계 P6). 각 갱신은 실패를 안에서 삼켜 서로를 막지 않는다.
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
        refundCaseMetrics.refresh();
    }
}

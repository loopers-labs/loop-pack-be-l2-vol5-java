package com.loopers.domains.order.in;

import com.loopers.domains.payment.in.RefundCaseMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

// 환불 확인 대상 gauge는 따로 스레드를 두지 않고 기존 지표 갱신 작업이 함께 갱신한다(계획 P6).
class UnresolvedPaymentMetricsSchedulerTest {
    @Test
    @DisplayName("지표 갱신 한 회차는 미확인 결제 gauge와 환불 확인 대상 gauge를 모두 갱신한다")
    void refreshesUnresolvedAndRefundCaseGauges() {
        UnresolvedPaymentMetrics unresolvedPaymentMetrics = mock(UnresolvedPaymentMetrics.class);
        RefundCaseMetrics refundCaseMetrics = mock(RefundCaseMetrics.class);
        UnresolvedPaymentMetricsScheduler scheduler = new UnresolvedPaymentMetricsScheduler(unresolvedPaymentMetrics,
            refundCaseMetrics);

        scheduler.refresh();

        verify(unresolvedPaymentMetrics).refresh();
        verify(refundCaseMetrics).refresh();
    }
}

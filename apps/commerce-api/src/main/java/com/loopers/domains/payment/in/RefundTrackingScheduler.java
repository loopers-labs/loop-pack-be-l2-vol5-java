package com.loopers.domains.payment.in;

import com.loopers.domains.payment.app.RefundTrackingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 환불 추적은 결제 재조회와 섞지 않고 전용 스레드(sched-payment-refund-)에서 돈다(사용자 결정: 작업마다 경계).
@Component
@ConditionalOnProperty(name = "payment.refund.scheduler.enabled", havingValue = "true")
public class RefundTrackingScheduler {
    public static final String TASK_SCHEDULER = "paymentRefundTaskScheduler";

    private final RefundTrackingService refundTrackingService;

    public RefundTrackingScheduler(RefundTrackingService refundTrackingService) {
        this.refundTrackingService = refundTrackingService;
    }

    @Scheduled(fixedDelayString = "${payment.refund.scheduler.fixed-delay:PT30S}", scheduler = TASK_SCHEDULER)
    public void runOnce() {
        refundTrackingService.trackDueCases();
    }
}

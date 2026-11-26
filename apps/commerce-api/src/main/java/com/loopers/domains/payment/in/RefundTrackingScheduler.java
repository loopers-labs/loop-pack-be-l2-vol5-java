package com.loopers.domains.payment.in;

import com.loopers.domains.payment.app.RefundTrackingService;

public class RefundTrackingScheduler {
    public static final String TASK_SCHEDULER = "paymentRefundTaskScheduler";

    private final RefundTrackingService refundTrackingService;

    public RefundTrackingScheduler(RefundTrackingService refundTrackingService) {
        this.refundTrackingService = refundTrackingService;
    }

    public void runOnce() {
    }
}

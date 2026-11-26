package com.loopers.domains.order.in;

import com.loopers.domains.order.app.OrderApplicationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "payment.lookup.scheduler.enabled", havingValue = "true")
public class PaymentLookupScheduler {
    public static final String TASK_SCHEDULER = "paymentLookupTaskScheduler";

    private final OrderApplicationService orderApplicationService;

    public PaymentLookupScheduler(OrderApplicationService orderApplicationService) {
        this.orderApplicationService = orderApplicationService;
    }

    @Scheduled(fixedDelayString = "${payment.lookup.scheduler.fixed-delay:PT1S}", scheduler = TASK_SCHEDULER)
    public void runOnce() {
        orderApplicationService.expireOverduePayments();
        orderApplicationService.reconcileDuePayments();
    }
}

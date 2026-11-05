package com.loopers.infrastructure.payment;

import com.loopers.application.order.OrderApplicationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "payment.lookup.scheduler.enabled", havingValue = "true")
public class PaymentLookupScheduler {
    private final OrderApplicationService orderApplicationService;

    public PaymentLookupScheduler(OrderApplicationService orderApplicationService) {
        this.orderApplicationService = orderApplicationService;
    }

    @Scheduled(fixedDelayString = "${payment.lookup.scheduler.fixed-delay:PT1S}")
    public void runOnce() {
        orderApplicationService.expireOverduePayments();
        orderApplicationService.reconcileDuePayments();
    }
}

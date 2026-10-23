package com.loopers.order.application;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class PendingPaymentRecoveryStartupListener {

    private final PendingPaymentRecoveryService recoveryService;

    public PendingPaymentRecoveryStartupListener(PendingPaymentRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverPendingPayments() {
        recoveryService.recoverPendingPayments();
    }
}

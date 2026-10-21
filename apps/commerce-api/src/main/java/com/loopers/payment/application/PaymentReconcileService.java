package com.loopers.payment.application;

import com.loopers.payment.application.port.in.PaymentInfo;
import com.loopers.payment.application.port.in.PaymentReconcileUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class PaymentReconcileService implements PaymentReconcileUseCase {

    @Override
    public PaymentInfo reconcile(Long userId, Long orderId) {
        return null;
    }

    @Override
    public void reconcileByCallback(String providerOrderId) {
    }
}

package com.loopers.application.payment;

import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentObservation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class PaymentReconcileFacade {

    private final PaymentTransactionService transactions;
    private final PaymentGateway gateway;
    private final PaymentObservationApplier applier;

    public PaymentApplyResult reconcile(Long userId, Long orderId) {
        VerificationTarget target = transactions.prepareVerification(orderId, userId, null);
        PaymentObservation observation = switch (target.kind()) {
            case TRANSACTION_KEY -> gateway.findByTransactionKey(target.target());
            case PROVIDER_ORDER_ID -> gateway.findByOrderId(target.target());
        };
        return applier.apply(target.token(), observation);
    }
}

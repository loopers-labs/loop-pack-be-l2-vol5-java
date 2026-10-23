package com.loopers.application.payment;

import com.loopers.domain.payment.PaymentGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class PaymentCallbackFacade {

    private final PaymentTransactionService transactions;
    private final PaymentGateway gateway;
    private final PaymentObservationApplier applier;

    public PaymentApplyResult handle(PaymentCallbackCommand notification) {
        Long orderId = transactions.resolveCallbackOrderId(notification.providerOrderId());
        VerificationTarget target = transactions.prepareVerification(orderId, null, notification.transactionKey());
        return applier.apply(target.token(), gateway.findByTransactionKey(target.target()));
    }
}

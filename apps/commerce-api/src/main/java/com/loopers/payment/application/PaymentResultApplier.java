package com.loopers.payment.application;

import com.loopers.payment.application.port.out.PgTransaction;
import com.loopers.payment.domain.PaymentApplyOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class PaymentResultApplier {

    public PaymentApplyOutcome apply(Long paymentId, PgTransaction observed) {
        return PaymentApplyOutcome.NOT_FINAL;
    }

    public PaymentApplyOutcome reconcile(Long paymentId) {
        return PaymentApplyOutcome.NOT_FINAL;
    }
}

package com.loopers.application.payment;

import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentCreation;
import com.loopers.domain.payment.PaymentGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class PaymentFacade {

    private final PaymentTransactions paymentTransactions;
    private final PaymentGateway paymentGateway;

    public Payment confirm(PaymentConfirmCommand command, Instant now) {
        PaymentBegin begin = paymentTransactions.begin(
            command.userId(), command.orderId(), command.attemptKey(), command.cardType(), now);

        return switch (begin) {
            case PaymentBegin.Repeated(Payment payment) -> payment;
            case PaymentBegin.Granted(Payment payment) -> {
                paymentGateway.create(new PaymentCreation(
                    payment.getProviderOrderId(), command.cardType(), command.cardNumber(), payment.getAmount()));
                yield payment;
            }
        };
    }
}

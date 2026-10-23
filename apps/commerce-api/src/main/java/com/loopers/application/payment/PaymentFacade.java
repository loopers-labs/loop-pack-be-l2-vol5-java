package com.loopers.application.payment;

import com.loopers.domain.payment.CreateObservation;
import com.loopers.domain.payment.LookupResult;
import com.loopers.domain.payment.PaymentDiscrepancy;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentCreation;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentService;
import com.loopers.domain.payment.ProviderOrderId;
import com.loopers.domain.payment.TransactionKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class PaymentFacade {

    public enum CallbackResult {
        APPLIED,
        UNKNOWN_ORDER,
        LOOKUP_UNAVAILABLE
    }

    private final PaymentTransactions paymentTransactions;
    private final PaymentGateway paymentGateway;
    private final PaymentService paymentService;

    public Payment confirm(PaymentConfirmCommand command, Instant now) {
        PaymentTransactions.Begin begin = paymentTransactions.begin(
            command.userId(), command.orderId(), command.attemptKey(), command.cardType(), now);

        return switch (begin) {
            case PaymentTransactions.Begin.Repeated(Payment payment) -> payment;
            case PaymentTransactions.Begin.Granted(Payment payment) -> {
                CreateObservation observation = paymentGateway.create(new PaymentCreation(
                    payment.getProviderOrderId(), command.cardType(), command.cardNumber(), payment.getAmount()));
                yield paymentTransactions.observe(payment.getId(), observation, now);
            }
        };
    }

    public CallbackResult onCallback(ProviderOrderId providerOrderId, TransactionKey transactionKey, Instant now) {
        Optional<Payment> payment = paymentService.findByProviderOrderId(providerOrderId);
        if (payment.isEmpty()) {
            return CallbackResult.UNKNOWN_ORDER;
        }
        return switch (paymentGateway.lookup(transactionKey)) {
            case LookupResult.Unavailable unavailable -> CallbackResult.LOOKUP_UNAVAILABLE;
            case LookupResult.Observation observation -> {
                paymentTransactions.observe(
                    payment.get().getId(), observation, PaymentDiscrepancy.Source.CALLBACK, now);
                yield CallbackResult.APPLIED;
            }
        };
    }
}

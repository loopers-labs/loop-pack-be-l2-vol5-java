package com.loopers.application.payment;

import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.CreateObservation;
import com.loopers.domain.payment.LookupResult;
import com.loopers.domain.payment.PaymentDiscrepancy;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentCreation;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentService;
import com.loopers.domain.payment.ProviderOrderId;
import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
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

    public record ReconcileResult(Payment payment, boolean lookupUnavailable) {
    }

    private final PaymentTransactions paymentTransactions;
    private final PaymentGateway paymentGateway;
    private final PaymentService paymentService;
    private final OrderService orderService;

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

    public CallbackResult onCallback(ProviderOrderId providerOrderId, Instant now) {
        Optional<Payment> payment = paymentService.findByProviderOrderId(providerOrderId);
        if (payment.isEmpty()) {
            return CallbackResult.UNKNOWN_ORDER;
        }
        return switch (paymentGateway.lookup(providerOrderId)) {
            case LookupResult.Unavailable unavailable -> CallbackResult.LOOKUP_UNAVAILABLE;
            case LookupResult.Observation observation -> {
                paymentTransactions.observe(
                    payment.get().getId(), observation, PaymentDiscrepancy.Source.CALLBACK, now);
                yield CallbackResult.APPLIED;
            }
        };
    }

    public ReconcileResult reconcile(Long userId, Long orderId, Instant now) {
        orderService.getOwned(userId, orderId);
        Payment payment = paymentService.findByOrderId(orderId)
            .orElseThrow(() -> new DomainException(DomainError.PAYMENT_NOT_FOUND));
        if (!payment.isAwaitingResult()) {
            return new ReconcileResult(payment, false);
        }
        LookupResult result = payment.getTransactionKey() != null
            ? paymentGateway.lookup(payment.getTransactionKey())
            : paymentGateway.lookup(payment.getProviderOrderId());
        return switch (result) {
            case LookupResult.Unavailable unavailable -> new ReconcileResult(payment, true);
            case LookupResult.Observation observation -> new ReconcileResult(paymentTransactions.observe(
                payment.getId(), observation, PaymentDiscrepancy.Source.LOOKUP, now), false);
        };
    }
}

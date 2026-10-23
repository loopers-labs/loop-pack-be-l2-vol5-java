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
import com.loopers.support.error.CoreException;
import com.loopers.support.error.DomainError;
import com.loopers.support.error.ErrorType;
import io.github.resilience4j.bulkhead.Bulkhead;
import com.loopers.support.error.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Slf4j
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
    private final Bulkhead createBulkhead;

    public Payment confirm(PaymentConfirmCommand command, Instant now) {
        if (!createBulkhead.tryAcquirePermission()) {
            throw new CoreException(ErrorType.PAYMENT_BUSY);
        }
        try {
            return start(command, now);
        } finally {
            createBulkhead.onComplete();
        }
    }

    private Payment start(PaymentConfirmCommand command, Instant now) {
        PaymentTransactions.Begin begin = paymentTransactions.begin(
            command.userId(), command.orderId(), command.attemptKey(), command.cardType(), now);

        return switch (begin) {
            case PaymentTransactions.Begin.Repeated(Payment payment) -> payment;
            case PaymentTransactions.Begin.Granted(Payment payment) -> {
                CreateObservation observation = paymentGateway.create(new PaymentCreation(
                    payment.getProviderOrderId(), command.cardType(), command.cardNumber(), payment.getAmount()));
                yield observeOrKeep(payment, observation, now);
            }
        };
    }

    private Payment observeOrKeep(Payment sent, CreateObservation observation, Instant now) {
        try {
            return paymentTransactions.observe(sent.getId(), observation, now);
        } catch (DataAccessException e) {
            log.warn("생성 응답을 반영하지 못해 저장된 상태로 응답한다: paymentId={}, observation={}",
                sent.getId(), observation, e);
            return sent;
        }
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

package com.loopers.application.payment;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.CardType;
import com.loopers.domain.payment.CreateObservation;
import com.loopers.domain.payment.LookupResult;
import com.loopers.domain.payment.PaymentDiscrepancy;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentAttemptKey;
import com.loopers.domain.payment.PaymentOutcome;
import com.loopers.domain.payment.PaymentService;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.product.StockDeduction;
import com.loopers.domain.product.StockRestoration;
import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import lombok.RequiredArgsConstructor;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

@Component
@RequiredArgsConstructor
public class PaymentTransactions {

    public sealed interface Begin {

        Payment payment();

        record Granted(Payment payment) implements Begin {
        }

        record Repeated(Payment payment) implements Begin {
        }
    }

    private final OrderService orderService;
    private final PaymentService paymentService;
    private final StockDeduction stockDeduction;
    private final StockRestoration stockRestoration;
    private final PaymentMetrics paymentMetrics;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public Begin begin(Long userId, Long orderId, PaymentAttemptKey attemptKey, CardType cardType,
                              Instant now) {
        Order order = orderService.getOwnedForUpdate(userId, orderId);

        Optional<Payment> existing = paymentService.findByAttemptKey(attemptKey);
        if (existing.isPresent()) {
            Payment payment = existing.get();
            if (!payment.isSameRequest(order.getId(), order.getTotalAmount(), cardType)) {
                throw new DomainException(DomainError.PAYMENT_ATTEMPT_CONFLICT);
            }
            return new Begin.Repeated(payment);
        }

        order.startPayment();
        order.getItems().stream()
            .sorted(Comparator.comparing(OrderItem::productId))
            .forEach(item -> stockDeduction.deductStock(item.productId(), item.quantity().toQuantity()));
        Payment payment = paymentService.save(
            Payment.request(order.getId(), attemptKey, order.getTotalAmount(), cardType, now));
        orderService.save(order);
        return new Begin.Granted(payment);
    }

    @Transactional
    public List<Payment> pickDueForCheck(Instant requestedBefore, Instant checkedBefore, int limit, Instant now) {
        List<Payment> due = paymentService.findDueForCheckSkippingLocked(requestedBefore, checkedBefore, limit);
        due.forEach(payment -> {
            payment.markChecked(now);
            paymentService.save(payment);
        });
        return due;
    }

    @Transactional
    public Payment observe(Long paymentId, CreateObservation observation, Instant now) {
        return apply(paymentId, payment -> payment.observe(observation),
            PaymentDiscrepancy.Source.CREATE_RESPONSE, now);
    }

    @Transactional
    public Payment observe(Long paymentId, LookupResult.Observation observation, PaymentDiscrepancy.Source source,
                           Instant now) {
        return apply(paymentId, payment -> {
            payment.markChecked(now);
            return payment.observe(observation);
        }, source, now);
    }

    private Payment apply(Long paymentId, Function<Payment, PaymentOutcome> observation,
                          PaymentDiscrepancy.Source source, Instant now) {
        Order order = orderService.getForUpdate(paymentService.orderIdOf(paymentId));
        Payment payment = paymentService.getForUpdate(paymentId);
        PaymentStatus before = payment.getStatus();

        PaymentOutcome outcome = observation.apply(payment);
        paymentMetrics.observed(before, payment.getStatus());

        switch (outcome.effect()) {
            case NONE -> {
            }
            case CONFIRM_ORDER -> {
                order.completePayment(now);
                orderService.save(order);
                order.pullEvents().forEach(eventPublisher::publishEvent);
            }
            case FAIL_ORDER -> {
                order.failPayment();
                order.getItems().stream()
                    .sorted(Comparator.comparing(OrderItem::productId))
                    .forEach(item -> stockRestoration.restoreStock(item.productId(), item.quantity().toQuantity()));
                orderService.save(order);
            }
        }
        if (outcome.discrepancy() != null) {
            paymentMetrics.discrepancy(outcome.discrepancy().reason());
            paymentService.recordDiscrepancy(paymentId, outcome.discrepancy(), source, now);
        }
        return paymentService.save(payment);
    }
}

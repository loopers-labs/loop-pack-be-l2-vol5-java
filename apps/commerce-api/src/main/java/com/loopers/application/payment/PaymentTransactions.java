package com.loopers.application.payment;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.CardType;
import com.loopers.domain.payment.CreateObservation;
import com.loopers.domain.payment.LookupObservation;
import com.loopers.domain.payment.ObservationSource;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentAttemptKey;
import com.loopers.domain.payment.PaymentOutcome;
import com.loopers.domain.payment.PaymentService;
import com.loopers.domain.product.StockDeduction;
import com.loopers.domain.product.StockRestoration;
import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Function;

@Component
@RequiredArgsConstructor
public class PaymentTransactions {

    private final OrderService orderService;
    private final PaymentService paymentService;
    private final StockDeduction stockDeduction;
    private final StockRestoration stockRestoration;

    @Transactional
    public PaymentBegin begin(Long userId, Long orderId, PaymentAttemptKey attemptKey, CardType cardType,
                              Instant now) {
        Order order = orderService.getOwnedForUpdate(userId, orderId);

        Optional<Payment> existing = paymentService.findByAttemptKey(attemptKey);
        if (existing.isPresent()) {
            Payment payment = existing.get();
            if (!payment.isSameRequest(order.getId(), order.getTotalAmount(), cardType)) {
                throw new DomainException(DomainError.PAYMENT_ATTEMPT_CONFLICT);
            }
            return new PaymentBegin.Repeated(payment);
        }

        order.startPayment();
        order.getItems().stream()
            .sorted(Comparator.comparing(OrderItem::productId))
            .forEach(item -> stockDeduction.deductStock(item.productId(), item.quantity().toQuantity()));
        Payment payment = paymentService.save(
            Payment.request(order.getId(), attemptKey, order.getTotalAmount(), cardType, now));
        orderService.save(order);
        return new PaymentBegin.Granted(payment);
    }

    @Transactional
    public Payment observe(Long paymentId, CreateObservation observation, Instant now) {
        return apply(paymentId, payment -> payment.observe(observation), ObservationSource.CREATE_RESPONSE, now);
    }

    @Transactional
    public Payment observe(Long paymentId, LookupObservation observation, ObservationSource source, Instant now) {
        return apply(paymentId, payment -> payment.observe(observation), source, now);
    }

    private Payment apply(Long paymentId, Function<Payment, PaymentOutcome> observation, ObservationSource source,
                          Instant now) {
        Order order = orderService.getForUpdate(paymentService.orderIdOf(paymentId));
        Payment payment = paymentService.getForUpdate(paymentId);

        PaymentOutcome outcome = observation.apply(payment);

        switch (outcome.effect()) {
            case NONE -> {
            }
            case CONFIRM_ORDER -> {
                order.completePayment(now);
                orderService.save(order);
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
            paymentService.recordDiscrepancy(paymentId, outcome.discrepancy(), source, now);
        }
        return paymentService.save(payment);
    }
}

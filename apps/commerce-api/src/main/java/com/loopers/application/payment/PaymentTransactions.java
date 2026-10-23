package com.loopers.application.payment;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.CardType;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentAttemptKey;
import com.loopers.domain.payment.PaymentService;
import com.loopers.domain.product.StockDeduction;
import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class PaymentTransactions {

    private final OrderService orderService;
    private final PaymentService paymentService;
    private final StockDeduction stockDeduction;

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
}

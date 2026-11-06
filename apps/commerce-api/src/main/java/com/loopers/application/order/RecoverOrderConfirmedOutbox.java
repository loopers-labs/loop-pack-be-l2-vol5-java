package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentResult;
import com.loopers.domain.payment.PaymentRepository;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.kafka.event.OrderConfirmed;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class RecoverOrderConfirmedOutbox {
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final OrderConfirmedOutbox outbox;

    @Transactional
    public boolean recover(long orderId) {
        Order order = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order does not exist"));
        var payment = payments.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Payment does not exist"));
        if (order.getStatus() != OrderStatus.CONFIRMED || order.getConfirmedAt() == null
                || order.getPaymentResult() != PaymentResult.SUCCESS || order.getPaidAmount() == null
                || order.getPaidAmount() != payment.amount() || payment.status() != PaymentStatus.SUCCESS) {
            throw new IllegalStateException("Confirmed order and successful payment are required");
        }
        var event = OrderConfirmed.confirmed(orderId, order.getConfirmedAt(), order.getItems().stream()
                .map(item -> new OrderConfirmed.Item(item.getProductId(), item.getQuantity()))
                .toList());
        return outbox.recordIfAbsent(event);
    }
}

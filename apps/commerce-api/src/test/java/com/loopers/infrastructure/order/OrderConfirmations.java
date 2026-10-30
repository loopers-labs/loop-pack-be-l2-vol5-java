package com.loopers.infrastructure.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderQuantity;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.Price;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

final class OrderConfirmations {

    private static final Instant PLACED_AT = Instant.parse("2026-10-30T00:00:00Z");
    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-30T01:02:03.12Z");

    private final OrderRepository orderRepository;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;

    OrderConfirmations(OrderRepository orderRepository, TransactionTemplate transactionTemplate,
                       ApplicationEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.transactionTemplate = transactionTemplate;
        this.eventPublisher = eventPublisher;
    }

    Long pendingOrderId() {
        Order draft = Order.draft(1L, List.of(
            OrderItem.of(8L, "셔츠", Price.of(1_000), OrderQuantity.of(1)),
            OrderItem.of(7L, "코트", Price.of(3_000), OrderQuantity.of(2))), PLACED_AT);
        draft.startPayment();
        return orderRepository.save(draft).getId();
    }

    void commit(Long orderId) {
        transactionTemplate.executeWithoutResult(status -> confirm(orderId));
    }

    void rollback(Long orderId) {
        transactionTemplate.executeWithoutResult(status -> {
            confirm(orderId);
            status.setRollbackOnly();
        });
    }

    Order stored(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow();
    }

    OrderStatus statusOf(Long orderId) {
        return stored(orderId).getStatus();
    }

    private void confirm(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.completePayment(CONFIRMED_AT);
        orderRepository.save(order);
        order.pullEvents().forEach(eventPublisher::publishEvent);
    }
}

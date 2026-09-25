package com.loopers.infrastructure.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;

import java.util.List;

final class OrderJpaMapper {

    private OrderJpaMapper() {}

    static Order toDomain(OrderJpaEntity entity) {
        List<OrderItem> items = entity.getItems().stream()
            .map(OrderJpaMapper::toDomain)
            .toList();
        return Order.reconstitute(
            entity.getId(),
            entity.getUserId(),
            entity.getStatus(),
            entity.getTotalAmount(),
            entity.getPaymentAmount(),
            entity.getPaymentResult(),
            items
        );
    }

    static OrderJpaEntity toNewEntity(Order order) {
        List<OrderItemJpaEntity> items = order.getItems().stream()
            .map(OrderJpaMapper::toJpaEntity)
            .toList();
        return OrderJpaEntity.create(
            order.getUserId(),
            order.getStatus(),
            order.getTotalAmount(),
            order.getPaymentAmount(),
            order.getPaymentResult(),
            items
        );
    }

    static void update(Order order, OrderJpaEntity entity) {
        entity.updateFrom(order);
    }

    private static OrderItem toDomain(OrderItemJpaEntity entity) {
        return OrderItem.create(entity.getProductId(), entity.getProductName(), entity.getUnitPrice(), entity.getQuantity());
    }

    private static OrderItemJpaEntity toJpaEntity(OrderItem item) {
        return OrderItemJpaEntity.create(item.getProductId(), item.getProductName(), item.getUnitPrice(), item.getQuantity());
    }
}

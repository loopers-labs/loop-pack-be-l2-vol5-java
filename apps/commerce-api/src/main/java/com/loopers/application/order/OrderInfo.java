package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderException;
import com.loopers.domain.order.OrderStatus;

import java.time.ZonedDateTime;
import java.util.List;

public record OrderInfo(Long orderId, OrderStatus status, List<ItemInfo> items, long totalAmount,
                        Long paidAmount, ZonedDateTime createdAt, ZonedDateTime confirmedAt) {
    public OrderInfo {
        items = List.copyOf(items);
    }

    public static OrderInfo from(Order order) {
        try {
            return new OrderInfo(order.getId(), order.getStatus(), order.getItems().stream()
                .map(item -> new ItemInfo(item.getProductId(), item.getProductName(), item.getUnitPrice(),
                    item.getQuantity(), item.subtotal())).toList(), order.getTotalAmount(), order.getPaidAmount(),
                order.getCreatedAt(), order.getConfirmedAt());
        } catch (OrderException exception) {
            if (exception.getReason() == OrderException.Reason.AMOUNT_LIMIT_EXCEEDED) {
                throw new IllegalStateException("Stored order amount exceeds its supported range", exception);
            }
            throw exception;
        }
    }

    public record ItemInfo(long productId, String productName, long unitPrice, int quantity, long subtotal) {
    }
}

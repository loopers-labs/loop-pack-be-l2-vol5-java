package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderItemInfo;
import com.loopers.domain.order.OrderStatus;

import java.util.List;

public class OrderV1Dto {
    public record OrderItemRequest(Long productId, int quantity) {}

    public record CreateRequest(List<OrderItemRequest> items) {}

    public record OrderItemResponse(Long productId, int quantity, Long unitPrice, Long subtotal) {
        public static OrderItemResponse from(OrderItemInfo info) {
            return new OrderItemResponse(info.productId(), info.quantity(), info.unitPrice(), info.subtotal());
        }
    }

    public record OrderResponse(
        Long id, OrderStatus status, List<OrderItemResponse> items, Long totalAmount, Long paidAmount
    ) {
        public static OrderResponse from(OrderInfo info) {
            return new OrderResponse(
                info.id(),
                info.status(),
                info.items().stream().map(OrderItemResponse::from).toList(),
                info.totalAmount(),
                info.paidAmount()
            );
        }
    }
}

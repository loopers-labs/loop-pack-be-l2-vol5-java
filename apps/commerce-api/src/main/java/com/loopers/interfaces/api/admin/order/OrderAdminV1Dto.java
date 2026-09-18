package com.loopers.interfaces.api.admin.order;

import com.loopers.application.order.OrderAdminInfo;
import com.loopers.application.order.OrderItemInfo;
import com.loopers.domain.order.OrderStatus;

import java.util.List;

public class OrderAdminV1Dto {
    public record OrderItemResponse(Long productId, int quantity, Long unitPrice, Long subtotal) {
        public static OrderItemResponse from(OrderItemInfo info) {
            return new OrderItemResponse(info.productId(), info.quantity(), info.unitPrice(), info.subtotal());
        }
    }

    public record OrderResponse(
        Long id, Long userId, OrderStatus status, List<OrderItemResponse> items, Long totalAmount, Long paidAmount
    ) {
        public static OrderResponse from(OrderAdminInfo info) {
            return new OrderResponse(
                info.id(),
                info.userId(),
                info.status(),
                info.items().stream().map(OrderItemResponse::from).toList(),
                info.totalAmount(),
                info.paidAmount()
            );
        }
    }
}

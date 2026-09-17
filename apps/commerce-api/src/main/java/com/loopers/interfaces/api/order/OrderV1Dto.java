package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderInfo;
import com.loopers.domain.common.Money;
import com.loopers.domain.order.OrderItemModel;
import com.loopers.domain.order.OrderModel;

import java.util.List;

public class OrderV1Dto {

    public record OrderItemRequest(Long productId, Long quantity) {
    }

    public record OrderCreateRequest(List<OrderItemRequest> items) {
    }

    public record OrderItemResponse(Long productId, Long quantity, Long unitPrice, Long amount) {
        public static OrderItemResponse from(OrderItemModel item) {
            return new OrderItemResponse(
                item.getProductId(),
                item.getQuantity(),
                item.getUnitPrice().toWon(),
                item.calculateAmount().toWon()
            );
        }

        public static OrderItemResponse from(OrderInfo.Item item) {
            return new OrderItemResponse(item.productId(), item.quantity(), item.unitPrice(), item.amount());
        }
    }

    public record OrderResponse(
        Long id,
        String status,
        Long orderTotal,
        Long usedPointAmount,
        Long paymentAmount,
        List<OrderItemResponse> items
    ) {
        public static OrderResponse from(OrderModel order) {
            Money paymentAmount = order.getPaymentAmount();
            return new OrderResponse(
                order.getId(),
                order.getStatus().name(),
                order.getOrderTotal().toWon(),
                order.getUsedPointAmount(),
                paymentAmount != null ? paymentAmount.toWon() : null,
                order.getItems().stream().map(OrderItemResponse::from).toList()
            );
        }

        public static OrderResponse from(OrderInfo info) {
            return new OrderResponse(
                info.id(),
                info.status(),
                info.orderTotal(),
                info.usedPointAmount(),
                info.paymentAmount(),
                info.items().stream().map(OrderItemResponse::from).toList()
            );
        }
    }
}

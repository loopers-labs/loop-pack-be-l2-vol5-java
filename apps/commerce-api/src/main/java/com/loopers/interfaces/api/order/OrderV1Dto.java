package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.loopers.application.order.OrderFacade.OrderRequestItem;
import com.loopers.application.order.OrderInfo;
import com.loopers.interfaces.api.serialization.ExactIntegerDeserializer;
import com.loopers.interfaces.api.serialization.ExactLongDeserializer;

import java.util.List;

public class OrderV1Dto {
    public record CreateRequest(List<OrderItemRequest> items) {
        public List<OrderRequestItem> toOrderRequestItems() {
            return items == null ? null : items.stream()
                .map(item -> item == null ? null : new OrderRequestItem(item.productId(), item.quantity()))
                .toList();
        }
    }

    public record OrderItemRequest(
        @JsonDeserialize(using = ExactLongDeserializer.class) Long productId,
        @JsonDeserialize(using = ExactIntegerDeserializer.class) int quantity
    ) {}

    public record OrderResponse(
        Long id,
        Long userId,
        String status,
        long totalAmount,
        Long paymentAmount,
        String paymentResult,
        List<OrderItemResponse> items
    ) {
        public static OrderResponse from(OrderInfo info) {
            return new OrderResponse(
                info.id(),
                info.userId(),
                info.status(),
                info.totalAmount(),
                info.paymentAmount(),
                info.paymentResult(),
                info.items().stream().map(OrderItemResponse::from).toList()
            );
        }
    }

    public record OrderItemResponse(Long productId, String productName, long unitPrice, int quantity, long amount) {
        static OrderItemResponse from(OrderInfo.OrderItemInfo item) {
            return new OrderItemResponse(item.productId(), item.productName(), item.unitPrice(), item.quantity(), item.amount());
        }
    }
}

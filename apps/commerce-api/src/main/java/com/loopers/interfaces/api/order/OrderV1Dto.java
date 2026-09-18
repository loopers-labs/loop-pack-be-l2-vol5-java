package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderInfo;

import java.util.List;

public class OrderV1Dto {
    public record CreateRequest(List<ItemRequest> items) {
        public record ItemRequest(Long productId, int quantity) {}
    }

    public record OrderResponse(
        Long orderId,
        Long userId,
        String status,
        long totalAmount,
        long paidAmount,
        List<ItemResponse> items
    ) {
        public record ItemResponse(Long productId, int quantity, long unitPrice, long amount) {
            public static ItemResponse from(OrderInfo.Item item) {
                return new ItemResponse(item.productId(), item.quantity(), item.unitPrice(), item.amount());
            }
        }

        public static OrderResponse from(OrderInfo info) {
            return new OrderResponse(
                info.orderId(),
                info.userId(),
                info.status(),
                info.totalAmount(),
                info.paidAmount(),
                info.items().stream().map(ItemResponse::from).toList()
            );
        }
    }
}

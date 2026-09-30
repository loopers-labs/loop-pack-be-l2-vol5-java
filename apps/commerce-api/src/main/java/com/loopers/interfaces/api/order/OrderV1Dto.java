package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderInfo;
import com.loopers.domain.order.OrderStatus;

import java.time.ZonedDateTime;
import java.util.List;

public class OrderV1Dto {
    public record CreateRequest(List<ItemRequest> items) {
        public record ItemRequest(Long productId, Integer quantity) {}
    }

    public record ItemResponse(Long productId, int quantity, Long unitPrice) {}

    public record OrderResponse(
        Long id,
        Long userId,
        OrderStatus status,
        List<ItemResponse> items,
        Long paidAmount,
        ZonedDateTime confirmedAt,
        ZonedDateTime createdAt
    ) {
        public static OrderResponse from(OrderInfo info) {
            return new OrderResponse(
                info.id(),
                info.userId(),
                info.status(),
                info.items().stream()
                    .map(item -> new ItemResponse(item.productId(), item.quantity(), item.unitPrice()))
                    .toList(),
                info.paidAmount(),
                info.confirmedAt(),
                info.createdAt()
            );
        }
    }

    public record OrderListResponse(List<OrderResponse> orders) {
        public static OrderListResponse from(List<OrderInfo> infos) {
            return new OrderListResponse(infos.stream().map(OrderResponse::from).toList());
        }
    }
}

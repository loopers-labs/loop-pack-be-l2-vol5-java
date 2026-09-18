package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderCreateCommand;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderQuantity;

import java.time.Instant;
import java.util.List;

public class OrderV1Dto {

    public record PlaceRequest(List<Line> lines) {
        public PlaceRequest {
            if (lines == null || lines.isEmpty()) {
                throw new IllegalArgumentException("주문 품목은 1개 이상이어야 합니다.");
            }
        }

        public record Line(Long productId, Integer quantity) {
            public Line {
                if (productId == null) {
                    throw new IllegalArgumentException("productId 는 필수입니다.");
                }
                if (quantity == null) {
                    throw new IllegalArgumentException("quantity 는 필수입니다.");
                }
                OrderQuantity.of(quantity);
            }
        }

        public OrderCreateCommand toCommand(Long userId) {
            return new OrderCreateCommand(userId, lines.stream()
                .map(line -> new OrderCreateCommand.Line(line.productId(), OrderQuantity.of(line.quantity())))
                .toList());
        }
    }

    public record OrderItemResponse(Long productId, String productName, long unitPrice, int quantity, long lineTotal) {
        static OrderItemResponse from(OrderItem item) {
            return new OrderItemResponse(
                item.productId(), item.productName(), item.unitPrice().value(),
                item.quantity().value(), item.lineTotal().amount());
        }
    }

    public record OrderResponse(
        Long id,
        String status,
        long totalAmount,
        Long paidAmount,
        Instant placedAt,
        Instant confirmedAt,
        List<OrderItemResponse> items
    ) {
        public static OrderResponse from(Order order) {
            return new OrderResponse(
                order.getId(),
                order.getStatus().name(),
                order.getTotalAmount().amount(),
                order.getPaidAmount() != null ? order.getPaidAmount().amount() : null,
                order.getPlacedAt(),
                order.getConfirmedAt(),
                order.getItems().stream().map(OrderItemResponse::from).toList());
        }
    }
}

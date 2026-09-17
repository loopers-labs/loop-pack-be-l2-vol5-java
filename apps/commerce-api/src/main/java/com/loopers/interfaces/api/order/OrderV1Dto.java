package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderItemCommand;

import java.time.ZonedDateTime;
import java.util.List;

public class OrderV1Dto {
    /** EP-10 요청. items 1개 이상, quantity 정수 > 0 (검증은 Model, ER-12/13). */
    public record CreateOrderRequest(List<ItemRequest> items) {
        public List<OrderItemCommand> toCommands() {
            return items == null ? null : items.stream().map(ItemRequest::toCommand).toList();
        }
    }

    public record ItemRequest(Long productId, Integer quantity) {
        OrderItemCommand toCommand() {
            return new OrderItemCommand(productId, quantity);
        }
    }

    /** 설계 4-3-0 OrderItemResponse. */
    public record OrderItemResponse(Long productId, Integer quantity, Long unitPrice, Long lineAmount) {
        static OrderItemResponse from(OrderInfo.Item item) {
            return new OrderItemResponse(item.productId(), item.quantity(), item.unitPrice(), item.lineAmount());
        }
    }

    /** 설계 4-3-0 OrderResponse (고객). paidAmount·confirmedAt 은 DRAFT 면 null. */
    public record OrderResponse(
        Long id,
        String status,
        Long totalAmount,
        Long paidAmount,
        ZonedDateTime confirmedAt,
        List<OrderItemResponse> items,
        ZonedDateTime createdAt
    ) {
        public static OrderResponse from(OrderInfo info) {
            return new OrderResponse(
                info.id(), info.status(), info.totalAmount(), info.paidAmount(), info.confirmedAt(),
                info.items().stream().map(OrderItemResponse::from).toList(), info.createdAt());
        }
    }
}

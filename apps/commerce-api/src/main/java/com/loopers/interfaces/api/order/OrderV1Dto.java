package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderItemCommand;

import java.util.List;

public class OrderV1Dto {
    public record CreateRequest(List<ItemRequest> items) {
        public List<OrderItemCommand> toCommands() {
            if (items == null) {
                return List.of();
            }
            return items.stream().map(item -> new OrderItemCommand(item.productId(), item.quantity())).toList();
        }
    }

    public record ItemRequest(Long productId, int quantity) {
    }

    public record ItemResponse(Long productId, int quantity, Long unitPrice, long amount) {
        public static ItemResponse from(OrderInfo.ItemInfo info) {
            return new ItemResponse(info.productId(), info.quantity(), info.unitPrice(), info.amount());
        }
    }

    public record OrderResponse(Long id, String status, long totalAmount, Long paidAmount, List<ItemResponse> items) {
        public static OrderResponse from(OrderInfo info) {
            return new OrderResponse(
                info.id(), info.status(), info.totalAmount(), info.paidAmount(),
                info.items().stream().map(ItemResponse::from).toList()
            );
        }
    }

    public record OrdersResponse(List<OrderResponse> items) {
        public static OrdersResponse from(List<OrderInfo> infos) {
            return new OrdersResponse(infos.stream().map(OrderResponse::from).toList());
        }
    }
}

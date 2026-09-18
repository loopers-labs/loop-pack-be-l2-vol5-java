package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderSummaryInfo;
import com.loopers.domain.order.OrderService.OrderRequestLine;

import java.time.ZonedDateTime;
import java.util.List;

public class OrderV1Dto {

    /** 수량은 원시 타입이라 누락 · 소수는 400 이다. items 가 없으면 빈 주문으로 보고 EMPTY_ORDER_ITEMS 로 거절한다. */
    public record CreateRequest(List<Item> items) {
        public record Item(long productId, int quantity) {}

        public List<OrderRequestLine> toLines() {
            if (items == null) {
                return List.of();
            }
            return items.stream().map(item -> new OrderRequestLine(item.productId(), item.quantity())).toList();
        }
    }

    public record OrderResponse(
        Long id,
        String status,
        long totalAmount,
        Long paymentAmount,
        ZonedDateTime paidAt,
        ZonedDateTime orderedAt,
        List<ItemResponse> items
    ) {
        public record ItemResponse(Long productId, String productName, long unitPrice, int quantity, long amount) {}

        public static OrderResponse from(OrderInfo info) {
            return new OrderResponse(
                info.id(),
                info.status(),
                info.totalAmount(),
                info.paymentAmount(),
                info.paidAt(),
                info.orderedAt(),
                info.items().stream()
                    .map(item -> new ItemResponse(item.productId(), item.productName(), item.unitPrice(), item.quantity(), item.amount()))
                    .toList()
            );
        }
    }

    public record OrderSummaryResponse(
        Long id,
        String status,
        long totalAmount,
        Long paymentAmount,
        int itemCount,
        String representativeProductName,
        ZonedDateTime orderedAt
    ) {
        public static OrderSummaryResponse from(OrderSummaryInfo info) {
            return new OrderSummaryResponse(
                info.id(),
                info.status(),
                info.totalAmount(),
                info.paymentAmount(),
                info.itemCount(),
                info.representativeProductName(),
                info.orderedAt()
            );
        }
    }
}

package com.loopers.interfaces.api.admin.order;

import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderSummaryInfo;

import java.time.ZonedDateTime;
import java.util.List;

public class AdminOrderV1Dto {

    /** 고객 응답과 달리 구매자 식별자를 담는다 (설계 6.5). */
    public record OrderSummaryResponse(
        Long id,
        Long userId,
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
                info.userId(),
                info.status(),
                info.totalAmount(),
                info.paymentAmount(),
                info.itemCount(),
                info.representativeProductName(),
                info.orderedAt()
            );
        }
    }

    public record OrderResponse(
        Long id,
        Long userId,
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
                info.userId(),
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
}

package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderSummaryInfo;
import com.loopers.domain.order.OrderLines;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.time.ZonedDateTime;
import java.util.List;

public class OrderV1Dto {

    public record CreateRequest(List<ItemRequest> items) {
        public List<OrderLines.Line> toLines() {
            if (items == null) {
                return List.of();
            }
            return items.stream().map(ItemRequest::toLine).toList();
        }
    }

    public record ItemRequest(Long productId, Integer quantity) {
        OrderLines.Line toLine() {
            if (quantity == null) {
                throw new CoreException(ErrorType.BAD_REQUEST, "주문 품목의 수량이 필요합니다.");
            }
            return new OrderLines.Line(productId, quantity);
        }
    }

    /**
     * 고객 주문 상세. 구매자 userId는 넣지 않는다 (본인 주문이므로).
     */
    public record OrderResponse(
        Long id,
        OrderStatus status,
        List<ItemResponse> items,
        long totalAmount,
        Long paidAmount,
        PaymentMethod paymentMethod,
        ZonedDateTime confirmedAt,
        ZonedDateTime createdAt
    ) {
        public static OrderResponse from(OrderInfo info) {
            return new OrderResponse(
                info.id(),
                info.status(),
                info.items().stream().map(ItemResponse::from).toList(),
                info.totalAmount(),
                info.paidAmount(),
                info.paymentMethod(),
                info.confirmedAt(),
                info.createdAt()
            );
        }
    }

    public record ItemResponse(Long productId, String productName, long unitPrice, int quantity, long amount) {
        public static ItemResponse from(OrderInfo.Item item) {
            return new ItemResponse(item.productId(), item.productName(), item.unitPrice(), item.quantity(), item.amount());
        }
    }

    public record OrderSummaryResponse(Long id, OrderStatus status, long totalAmount, Long paidAmount, ZonedDateTime createdAt) {
        public static OrderSummaryResponse from(OrderSummaryInfo info) {
            return new OrderSummaryResponse(info.id(), info.status(), info.totalAmount(), info.paidAmount(), info.createdAt());
        }
    }
}

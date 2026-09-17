package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderItemInfo;
import com.loopers.application.order.OrderLineCommand;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.time.ZonedDateTime;
import java.util.List;

public class OrderDto {
    public record CreateRequest(List<ItemRequest> items) {
        // 누락은 요청 변환 단계에서 거절한다. 빈 목록·수량 범위는 도메인 규칙이다(7-0)
        public List<OrderLineCommand> toCommands() {
            if (items == null) {
                throw new CoreException(ErrorType.BAD_REQUEST, "주문 품목은 필수입니다.");
            }
            return items.stream().map(ItemRequest::toCommand).toList();
        }
    }

    public record ItemRequest(Long productId, Long quantity) {
        private OrderLineCommand toCommand() {
            if (productId == null || quantity == null) {
                throw new CoreException(ErrorType.BAD_REQUEST, "주문 품목의 상품 ID와 수량은 필수입니다.");
            }
            return new OrderLineCommand(productId, quantity);
        }
    }

    public record OrderResponse(
        Long orderId,
        String status,
        List<ItemResponse> items,
        Long totalAmount,
        Long paidAmount,
        ZonedDateTime createdAt,
        ZonedDateTime confirmedAt
    ) {
        public static OrderResponse from(OrderInfo info) {
            return new OrderResponse(
                info.orderId(),
                info.status(),
                info.items().stream().map(ItemResponse::from).toList(),
                info.totalAmount(),
                info.paidAmount(),
                info.createdAt(),
                info.confirmedAt()
            );
        }
    }

    public record ItemResponse(Long productId, String productName, Long quantity, Long unitPrice, Long amount) {
        public static ItemResponse from(OrderItemInfo info) {
            return new ItemResponse(info.productId(), info.productName(), info.quantity(), info.unitPrice(), info.amount());
        }
    }
}

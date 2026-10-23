package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderCommand;
import com.loopers.application.order.OrderConfirmInfo;
import com.loopers.application.order.OrderInfo;

import java.util.List;

public class OrderV1Dto {
    public record CreateRequest(List<ItemRequest> items) {
        public record ItemRequest(Long productId, int quantity) {}
    }

    /**
     * 주문 확정 요청. 금액은 저장된 주문에서 정한다. 카드 번호는 toString 에서도 가린다.
     */
    public record ConfirmRequest(String paymentAttemptKey, String cardType, String cardNo) {
        public OrderCommand.Confirm toCommand() {
            return new OrderCommand.Confirm(paymentAttemptKey, cardType, cardNo);
        }

        @Override
        public String toString() {
            return "ConfirmRequest[paymentAttemptKey=" + paymentAttemptKey
                + ", cardType=" + cardType
                + ", cardNo=****]";
        }
    }

    /**
     * 202(진행 중)·200(종결) 공통 응답. 주문 상태·시도 상태·조회 경로를 담고, transactionKey 와 카드 번호는 담지 않는다.
     */
    public record ConfirmResponse(Long orderId, String orderStatus, String attemptStatus, Links links) {
        public record Links(String order, String reconcile) {}

        public static ConfirmResponse from(OrderConfirmInfo info) {
            Long orderId = info.order().orderId();
            return new ConfirmResponse(
                orderId,
                info.order().status(),
                info.attemptStatus(),
                new Links("/api/v1/orders/" + orderId, "/api/v1/orders/" + orderId + "/payment/reconcile")
            );
        }
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

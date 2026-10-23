package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderDetailInfo;
import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderSummaryInfo;
import com.loopers.application.order.OrderFacade.OrderRequestLine;
import com.loopers.application.order.facade.OrderPaymentFacade.ConfirmCommand;

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

    /** 금액은 받지 않고 저장된 주문에서 정함 */
    public record ConfirmRequest(String paymentAttemptKey, String cardType, String cardNo) {
        public ConfirmCommand toCommand() {
            return new ConfirmCommand(paymentAttemptKey, cardType, cardNo);
        }

        @Override
        public String toString() {
            return "ConfirmRequest[paymentAttemptKey=" + paymentAttemptKey + ", cardType=" + cardType + ", cardNo=****]";
        }
    }

    /** paymentCheck 는 이번 요청의 PG 조회 실패 시에만 UNAVAILABLE. 충돌 사유는 고객에게 내보내지 않음 */
    public record OrderResponse(
        Long id,
        String status,
        long totalAmount,
        Long paymentAmount,
        ZonedDateTime paidAt,
        ZonedDateTime orderedAt,
        List<ItemResponse> items,
        String paymentMethod,
        String paymentStatus,
        String paymentCheck,
        String statusUrl
    ) {
        private static final String UNAVAILABLE = "UNAVAILABLE";

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
                    .toList(),
                info.paymentMethod(),
                info.paymentStatus(),
                info.paymentUnavailable() ? UNAVAILABLE : null,
                "/api/v1/orders/" + info.id()
            );
        }
    }

    /** 내 주문 상세. 품목마다 판매 여부를 담아 삭제된 상품은 판매종료로 보여줄 수 있게 함 (설계 6.4) */
    public record OrderDetailResponse(
        Long id,
        String status,
        long totalAmount,
        Long paymentAmount,
        ZonedDateTime paidAt,
        ZonedDateTime orderedAt,
        List<ItemResponse> items,
        String paymentMethod,
        String paymentStatus
    ) {
        public record ItemResponse(Long productId, String productName, long unitPrice, int quantity, long amount, boolean onSale) {}

        public static OrderDetailResponse from(OrderDetailInfo info) {
            return new OrderDetailResponse(
                info.id(),
                info.status(),
                info.totalAmount(),
                info.paymentAmount(),
                info.paidAt(),
                info.orderedAt(),
                info.items().stream()
                    .map(item -> new ItemResponse(
                        item.productId(), item.productName(), item.unitPrice(), item.quantity(), item.amount(), item.onSale()))
                    .toList(),
                info.paymentMethod(),
                info.paymentStatus()
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
        ZonedDateTime orderedAt,
        String paymentMethod,
        String paymentStatus
    ) {
        public static OrderSummaryResponse from(OrderSummaryInfo info) {
            return new OrderSummaryResponse(
                info.id(),
                info.status(),
                info.totalAmount(),
                info.paymentAmount(),
                info.itemCount(),
                info.representativeProductName(),
                info.orderedAt(),
                info.paymentMethod(),
                info.paymentStatus()
            );
        }
    }
}

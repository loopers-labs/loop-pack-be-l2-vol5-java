package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.loopers.application.order.OrderFacade.OrderRequestItem;
import com.loopers.application.order.OrderInfo;
import com.loopers.application.payment.PaymentStatusInfo;
import com.loopers.application.payment.PaymentReflection;
import com.loopers.application.payment.PaymentReflectionOutcome;
import com.loopers.interfaces.api.serialization.ExactIntegerDeserializer;
import com.loopers.interfaces.api.serialization.ExactLongDeserializer;
import com.loopers.interfaces.api.serialization.StrictStringDeserializer;

import java.util.List;

public class OrderV1Dto {
    public record CreateRequest(List<OrderItemRequest> items) {
        public List<OrderRequestItem> toOrderRequestItems() {
            return items == null ? null : items.stream()
                .map(item -> item == null ? null : new OrderRequestItem(item.productId(), item.quantity()))
                .toList();
        }
    }

    public record OrderItemRequest(
        @JsonDeserialize(using = ExactLongDeserializer.class) Long productId,
        @JsonDeserialize(using = ExactIntegerDeserializer.class) int quantity
    ) {}

    // 카드 입력은 PG 생성 요청에만 전달하고 저장·로그하지 않는다.
    public record ConfirmRequest(
        @JsonDeserialize(using = StrictStringDeserializer.class) String paymentAttemptKey,
        @JsonDeserialize(using = StrictStringDeserializer.class) String cardType,
        @JsonDeserialize(using = StrictStringDeserializer.class) String cardNo
    ) {
        @Override
        public String toString() {
            return "ConfirmRequest[redacted]";
        }
    }

    // 저장된 결제 상태는 비어 있는 필드도 null로 내보내고, 조회 불가 여부는 reconcile에만 포함한다.
    // 거래 키·충돌 사유·시도 키는 고객 응답에 넣지 않는다.
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PaymentStatusResponse(
        Long orderId,
        String orderStatus,
        String paymentMethod,
        String paymentProgress,
        String attemptStatus,
        String paymentResult,
        Long paymentAmount,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean lookupUnavailable,
        Links links
    ) {
        public static PaymentStatusResponse from(PaymentStatusInfo info) {
            return from(info, null);
        }

        public static PaymentStatusResponse from(PaymentReflection reflection) {
            return from(reflection.payment(), reflection.outcome() == PaymentReflectionOutcome.LOOKUP_UNAVAILABLE);
        }

        private static PaymentStatusResponse from(PaymentStatusInfo info, Boolean lookupUnavailable) {
            String orderPath = "/api/v1/orders/" + info.orderId();
            return new PaymentStatusResponse(
                info.orderId(),
                info.orderStatus().name(),
                info.paymentMethod() == null ? null : info.paymentMethod().name(),
                info.paymentProgress().name(),
                info.attemptStatus() == null ? null : info.attemptStatus().name(),
                info.paymentResult() == null ? null : info.paymentResult().name(),
                info.paymentAmount(),
                lookupUnavailable,
                new Links(orderPath, info.isUnterminated() ? orderPath + "/payment/reconcile" : null)
            );
        }
    }

    // 재확인 경로는 결제 대기 주문에만 포함한다.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Links(String order, String reconcile) {}

    public record OrderResponse(
        Long id,
        Long userId,
        String status,
        long totalAmount,
        Long paymentAmount,
        String paymentResult,
        List<OrderItemResponse> items,
        PaymentStatusResponse payment
    ) {
        public static OrderResponse from(OrderInfo info) {
            return new OrderResponse(
                info.id(),
                info.userId(),
                info.status(),
                info.totalAmount(),
                info.paymentAmount(),
                info.paymentResult(),
                info.items().stream().map(OrderItemResponse::from).toList(),
                PaymentStatusResponse.from(info.payment())
            );
        }
    }

    public record OrderItemResponse(Long productId, String productName, long unitPrice, int quantity, long amount) {
        public static OrderItemResponse from(OrderInfo.OrderItemInfo item) {
            return new OrderItemResponse(item.productId(), item.productName(), item.unitPrice(), item.quantity(), item.amount());
        }
    }
}

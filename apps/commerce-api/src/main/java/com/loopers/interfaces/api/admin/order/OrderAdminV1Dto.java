package com.loopers.interfaces.api.admin.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.loopers.application.order.OrderInfo;
import com.loopers.application.payment.PaymentStatusInfo;
import com.loopers.interfaces.api.order.OrderV1Dto;

import java.util.List;

public class OrderAdminV1Dto {

    public record OrderResponse(
        Long id,
        Long userId,
        String status,
        long totalAmount,
        Long paymentAmount,
        String paymentResult,
        List<OrderV1Dto.OrderItemResponse> items,
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
                info.items().stream().map(OrderV1Dto.OrderItemResponse::from).toList(),
                PaymentStatusResponse.from(info.payment())
            );
        }
    }

    // 고객 결제 상태 객체와 같은 형태에 거래 키·충돌 사유를 더한다. 고객 경로(links)와 시도 키는 넣지 않는다.
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PaymentStatusResponse(
        Long orderId,
        String orderStatus,
        String paymentMethod,
        String paymentProgress,
        String attemptStatus,
        String paymentResult,
        Long paymentAmount,
        String transactionKey,
        String conflictReason
    ) {
        public static PaymentStatusResponse from(PaymentStatusInfo info) {
            return new PaymentStatusResponse(
                info.orderId(),
                info.orderStatus().name(),
                info.paymentMethod() == null ? null : info.paymentMethod().name(),
                info.paymentProgress().name(),
                info.attemptStatus() == null ? null : info.attemptStatus().name(),
                info.paymentResult() == null ? null : info.paymentResult().name(),
                info.paymentAmount(),
                info.transactionKey(),
                info.conflictReason() == null ? null : info.conflictReason().name()
            );
        }
    }
}

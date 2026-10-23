package com.loopers.interfaces.api.admin.order;

import com.loopers.application.order.OrderAdminInfo;
import com.loopers.application.order.OrderItemInfo;
import com.loopers.application.order.PaymentInfo;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentConflict;
import com.loopers.domain.payment.UnknownReason;

import java.util.List;

public class OrderAdminV1Dto {
    public record OrderItemResponse(Long productId, int quantity, Long unitPrice, Long subtotal) {
        public static OrderItemResponse from(OrderItemInfo info) {
            return new OrderItemResponse(info.productId(), info.quantity(), info.unitPrice(), info.subtotal());
        }
    }

    /** 운영자가 미확인·충돌 결제를 판단할 수 있도록 거래 키, 결과 미확인 이유, 기록된 충돌까지 담는다. 카드번호는 어디에도 없다. */
    public record PaymentResponse(
        PaymentAttemptStatus status, String transactionKey, UnknownReason unknownReason, PaymentConflict conflict
    ) {
        public static PaymentResponse from(PaymentInfo info) {
            return new PaymentResponse(info.status(), info.transactionKey(), info.unknownReason(), info.conflict());
        }
    }

    public record OrderResponse(
        Long id,
        Long userId,
        OrderStatus status,
        List<OrderItemResponse> items,
        Long totalAmount,
        Long paidAmount,
        PaymentMethod paymentMethod,
        PaymentResponse payment
    ) {
        public static OrderResponse from(OrderAdminInfo info) {
            return new OrderResponse(
                info.id(),
                info.userId(),
                info.status(),
                info.items().stream().map(OrderItemResponse::from).toList(),
                info.totalAmount(),
                info.paidAmount(),
                info.paymentMethod(),
                info.payment() == null ? null : PaymentResponse.from(info.payment())
            );
        }
    }
}

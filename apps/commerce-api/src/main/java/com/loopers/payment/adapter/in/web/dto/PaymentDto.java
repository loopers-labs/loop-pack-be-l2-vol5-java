package com.loopers.payment.adapter.in.web.dto;

import com.loopers.order.domain.OrderStatus;
import com.loopers.payment.application.port.in.PaymentInfo;
import com.loopers.payment.application.port.in.PaymentRequestCommand;
import com.loopers.payment.domain.PaymentStatus;

public class PaymentDto {

    /**
     * 주문 확정 요청 본문. 카드 번호는 PG 요청까지 메모리로만 전달하고 저장·로그하지 않는다 (plan.md 8).
     */
    public record ConfirmRequest(String cardType, String cardNo) {

        public PaymentRequestCommand toCommand(Long userId, Long orderId, String idempotencyKey) {
            return new PaymentRequestCommand(userId, orderId, idempotencyKey, cardType, cardNo);
        }

        @Override
        public String toString() {
            return "ConfirmRequest[cardType=" + cardType + "]";
        }
    }

    /**
     * 주문의 결제 상태. 카드 정보는 없다.
     */
    public record PaymentResponse(
        Long orderId,
        OrderStatus orderStatus,
        PaymentStatus paymentStatus,
        String failureReason,
        String transactionKey
    ) {
        public static PaymentResponse from(PaymentInfo info) {
            return new PaymentResponse(info.orderId(), info.orderStatus(), info.paymentStatus(), info.failureReason(), info.transactionKey());
        }
    }

    /**
     * PG callback 본문 (과제 2절). orderId는 우리가 보낸 PG 주문 식별자(LP-ORD-…)다. 상태는 계기일 뿐 믿지 않는다.
     */
    public record CallbackRequest(String transactionKey, String orderId, String status) {}
}

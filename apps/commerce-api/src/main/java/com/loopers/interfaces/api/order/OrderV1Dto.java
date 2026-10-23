package com.loopers.interfaces.api.order;

import com.loopers.application.order.ConfirmPaymentCommand;
import com.loopers.application.order.OrderInfo;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.domain.payment.PaymentAttemptStatus;

import java.time.ZonedDateTime;
import java.util.List;

public class OrderV1Dto {
    public record CreateRequest(List<ItemRequest> items) {
        public record ItemRequest(Long productId, Integer quantity) {}
    }

    /** 확정 = PG 결제 시작. 금액은 받지 않는다 — 저장된 주문에서 정한다(docs/week5/pg-contract.md 6번). */
    public record ConfirmRequest(String paymentAttemptKey, String cardType, String cardNo) {
        public ConfirmPaymentCommand toCommand() {
            return new ConfirmPaymentCommand(paymentAttemptKey, cardType, cardNo);
        }

        @Override
        public String toString() {
            return "ConfirmRequest[paymentAttemptKey=" + paymentAttemptKey + ", cardType=" + cardType + ", cardNo=****]";
        }
    }

    public record ItemResponse(Long productId, int quantity, Long unitPrice) {}

    /** 결제 시도 상태는 그대로 싣는다. transactionKey·providerOrderId는 PG 쪽 식별자라 고객 응답에 넣지 않는다(D-023). */
    public record PaymentResponse(String attemptKey, PaymentAttemptStatus status, long amount) {}

    /** 다음에 요청할 경로. reconcile은 결제가 끝나지 않았을 때만 준다(D-023). */
    public record LinksResponse(String order, String reconcile) {}

    public record OrderResponse(
        Long id,
        Long userId,
        OrderStatus status,
        PaymentMethod paymentMethod,
        PaymentResponse payment,
        List<ItemResponse> items,
        Long paidAmount,
        ZonedDateTime confirmedAt,
        ZonedDateTime createdAt,
        LinksResponse links
    ) {
        public static OrderResponse from(OrderInfo info) {
            OrderInfo.Payment payment = info.payment();
            return new OrderResponse(
                info.id(),
                info.userId(),
                info.status(),
                info.paymentMethod(),
                payment == null ? null : new PaymentResponse(payment.attemptKey(), payment.status(), payment.amount()),
                info.items().stream()
                    .map(item -> new ItemResponse(item.productId(), item.quantity(), item.unitPrice()))
                    .toList(),
                info.paidAmount(),
                info.confirmedAt(),
                info.createdAt(),
                new LinksResponse(
                    "/api/v1/orders/" + info.id(),
                    paymentInProgress(info) ? "/api/v1/orders/" + info.id() + "/payment/reconcile" : null
                )
            );
        }

        /** 결제 시도가 있고 아직 끝나지 않았다 — confirm 응답은 202, 끝났거나 결제가 없으면 200(docs/week5/pg-contract.md 6번). */
        static boolean paymentInProgress(OrderInfo info) {
            return info.payment() != null && !info.payment().status().isTerminal();
        }
    }

    public record OrderListResponse(List<OrderResponse> orders) {
        public static OrderListResponse from(List<OrderInfo> infos) {
            return new OrderListResponse(infos.stream().map(OrderResponse::from).toList());
        }
    }
}

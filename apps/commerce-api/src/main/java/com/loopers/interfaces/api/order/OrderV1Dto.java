package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderItemInfo;
import com.loopers.application.order.PaymentInfo;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.domain.payment.PaymentAttemptStatus;

import java.util.List;

public class OrderV1Dto {
    public record OrderItemRequest(Long productId, int quantity) {}

    public record CreateRequest(List<OrderItemRequest> items) {}

    /**
     * PG 결제 시작 요청. 금액은 받지 않고 저장된 주문 합계로 정한다. 카드 값은 합성 값만 쓰고, 카드번호는 저장하지 않는다.
     * 카드번호가 응답이나 로그에 남지 않도록 record 기본 toString을 가린다.
     */
    public record ConfirmRequest(String paymentAttemptKey, String cardType, String cardNo) {
        @Override
        public String toString() {
            return "ConfirmRequest[paymentAttemptKey=" + paymentAttemptKey + ", cardType=" + cardType + ", cardNo=****]";
        }
    }

    public record OrderItemResponse(Long productId, int quantity, Long unitPrice, Long subtotal) {
        public static OrderItemResponse from(OrderItemInfo info) {
            return new OrderItemResponse(info.productId(), info.quantity(), info.unitPrice(), info.subtotal());
        }
    }

    /**
     * 고객에게 보이는 결제 상태. 거래 키·충돌 같은 PG 내부 값은 담지 않는다.
     *
     * @param lookupUnavailable 이번 호출에서 PG에 물어봤지만 확인하지 못했다. 결제를 실패로 정했다는 뜻이 아니다
     */
    public record PaymentResponse(PaymentAttemptStatus status, Boolean lookupUnavailable) {
        public static PaymentResponse from(PaymentInfo info) {
            return new PaymentResponse(info.status(), info.lookupUnavailable() ? Boolean.TRUE : null);
        }
    }

    /**
     * @param payment       PG 결제 시도가 없으면(DRAFT, 이전 POINT 주문) 비어 있다
     * @param orderPath     결제 결과를 기다리거나 모르는 주문에서만, 저장된 주문·결제 상태를 조회할 경로를 알린다 (GET, 새 결제 요청을 만들지 않는다)
     * @param reconcilePath 같은 주문에서만, PG의 기존 거래를 조회해 결과를 확인·적용할 경로를 알린다 (POST)
     */
    public record OrderResponse(
        Long id,
        OrderStatus status,
        List<OrderItemResponse> items,
        Long totalAmount,
        Long paidAmount,
        PaymentMethod paymentMethod,
        PaymentResponse payment,
        String orderPath,
        String reconcilePath
    ) {
        public static OrderResponse from(OrderInfo info) {
            boolean waiting = info.status() == OrderStatus.PAYMENT_PENDING;
            return new OrderResponse(
                info.id(),
                info.status(),
                info.items().stream().map(OrderItemResponse::from).toList(),
                info.totalAmount(),
                info.paidAmount(),
                info.paymentMethod(),
                info.payment() == null ? null : PaymentResponse.from(info.payment()),
                waiting ? "/api/v1/orders/" + info.id() : null,
                waiting ? "/api/v1/orders/" + info.id() + "/payment/reconcile" : null
            );
        }
    }
}

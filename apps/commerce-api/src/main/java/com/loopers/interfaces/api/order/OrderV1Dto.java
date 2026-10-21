package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderItemCommand;
import com.loopers.application.order.query.OrderView;

import java.time.ZonedDateTime;
import java.util.List;

public class OrderV1Dto {
    /** EP-10 요청. items 1개 이상, quantity 정수 > 0 (검증은 Model, ER-12/13). */
    public record CreateOrderRequest(List<ItemRequest> items) {
        public List<OrderItemCommand> toCommands() {
            return items == null ? null : items.stream().map(ItemRequest::toCommand).toList();
        }
    }

    public record ItemRequest(Long productId, Integer quantity) {
        OrderItemCommand toCommand() {
            return new OrderItemCommand(productId, quantity);
        }
    }

    /** EP-11 요청. 결제 시도 키(클라이언트 생성 멱등키)·카드번호·카드 종류. */
    public record ConfirmOrderRequest(String paymentAttemptKey, String cardNumber, String cardType) {
    }

    /** 설계 4-3-0 OrderItemResponse. */
    public record OrderItemResponse(Long productId, Integer quantity, Long unitPrice, Long lineAmount) {
        static OrderItemResponse from(OrderInfo.Item item) {
            return new OrderItemResponse(item.productId(), item.quantity(), item.unitPrice(), item.lineAmount());
        }

        static OrderItemResponse from(OrderView.Item item) {
            return new OrderItemResponse(item.productId(), item.quantity(), item.unitPrice(), item.lineAmount());
        }
    }

    /** 설계 4-3-0 OrderResponse (고객). paidAmount·confirmedAt 은 DRAFT 면 null. */
    public record OrderResponse(
        Long id,
        String status,
        Long totalAmount,
        Long paidAmount,
        ZonedDateTime confirmedAt,
        List<OrderItemResponse> items,
        ZonedDateTime createdAt
    ) {
        public static OrderResponse from(OrderInfo info) {
            return new OrderResponse(
                info.id(), info.status(), info.totalAmount(), info.paidAmount(), info.confirmedAt(),
                info.items().stream().map(OrderItemResponse::from).toList(), info.createdAt());
        }

        public static OrderResponse from(OrderView.Detail view) {
            return new OrderResponse(
                view.id(), view.status(), view.totalAmount(), view.paidAmount(), view.confirmedAt(),
                view.items().stream().map(OrderItemResponse::from).toList(), view.createdAt());
        }
    }

    /** EP-13 내 주문 상세. OrderResponse 에 결제 정보를 더한다. payment 는 DRAFT 면 null. PG 거래 키는 내보내지 않는다. */
    public record MyOrderResponse(
        Long id,
        String status,
        Long totalAmount,
        Long paidAmount,
        ZonedDateTime confirmedAt,
        PaymentResponse payment,
        List<OrderItemResponse> items,
        ZonedDateTime createdAt
    ) {
        public static MyOrderResponse from(OrderView.MyDetail view) {
            return new MyOrderResponse(
                view.id(), view.status(), view.totalAmount(), view.paidAmount(), view.confirmedAt(),
                PaymentResponse.from(view.payment()),
                view.items().stream().map(OrderItemResponse::from).toList(), view.createdAt());
        }
    }

    /** 결제수단(카드 종류, 마스킹한 카드번호)과 결제 시도 키. */
    public record PaymentResponse(String cardType, String maskedCardNo, String paymentAttemptKey) {
        static PaymentResponse from(OrderView.Payment payment) {
            return payment == null ? null : new PaymentResponse(payment.cardType(), payment.maskedCardNo(), payment.paymentAttemptKey());
        }
    }
}

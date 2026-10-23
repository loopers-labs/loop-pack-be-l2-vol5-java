package com.loopers.order.interfaces;

import com.loopers.interfaces.api.RequestFields;
import com.loopers.order.application.OrderUseCase;
import com.loopers.order.domain.Order;
import com.loopers.order.domain.OrderItem;
import com.loopers.order.domain.OrderStatus;
import com.loopers.order.domain.Payment;
import com.loopers.order.domain.PaymentMethod;
import com.loopers.order.domain.PaymentStatus;
import com.loopers.order.domain.PaymentResult;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.ZonedDateTime;
import java.util.List;

public class OrderV1Dto {

    public record CreateOrderRequest(List<ItemRequest> items) {
        public List<OrderUseCase.ItemCommand> toCommands() {
            return RequestFields.required(items).stream()
                .map(item -> RequestFields.required(item).toCommand())
                .toList();
        }
    }

    public record ConfirmPaymentRequest(
        String paymentKey,
        String paymentAttemptKey
    ) {
        public String requiredPaymentKey() {
            return RequestFields.required(paymentKey);
        }

        public String requiredPaymentAttemptKey() {
            return RequestFields.required(paymentAttemptKey);
        }
    }

    public record ItemRequest(Long productId, Integer quantity) {
        OrderUseCase.ItemCommand toCommand() {
            return new OrderUseCase.ItemCommand(
                RequestFields.required(productId),
                RequestFields.required(quantity)
            );
        }
    }

    public record OrderItemResponse(
        Long productId,
        String productName,
        int quantity,
        long unitPrice,
        long amount
    ) {
        static OrderItemResponse from(OrderItem item) {
            return new OrderItemResponse(
                item.productId(),
                item.productName(),
                item.quantity(),
                item.unitPrice(),
                item.amount()
            );
        }

        static List<OrderItemResponse> from(Order order) {
            return order.getItems().stream().map(OrderItemResponse::from).toList();
        }
    }

    public record PaymentResponse(PaymentMethod method, PaymentStatus status, Long amount, ZonedDateTime paidAt) {
        static PaymentResponse from(Order order) {
            Payment payment = order.getPayment();
            if (payment != null) {
                return new PaymentResponse(
                    payment.getMethod(), payment.status(), payment.getAmount(), payment.getPaidAt());
            }
            PaymentResult result = order.getPaymentResult();
            if (result == null) {
                return null;
            }
            return new PaymentResponse(PaymentMethod.POINT, PaymentStatus.SUCCESS, result.amount(), result.paidAt());
        }
    }

    public record OrderDetailResponse(
        Long id,
        OrderStatus status,
        List<OrderItemResponse> items,
        long totalAmount,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        PaymentResponse payment
    ) {
        public static OrderDetailResponse from(Order order) {
            return new OrderDetailResponse(
                order.getId(),
                order.getStatus(),
                OrderItemResponse.from(order),
                order.getTotalAmount(),
                PaymentResponse.from(order)
            );
        }
    }

    public record PendingPaymentResponse(OrderStatus orderStatus, PaymentStatus paymentStatus, String statusUrl) {
        public static PendingPaymentResponse from(Order order) {
            return new PendingPaymentResponse(
                order.getStatus(),
                order.getPayment().status(),
                "/api/v1/orders/" + order.getId()
            );
        }
    }

    public record OrderSummaryResponse(
        Long id,
        OrderStatus status,
        int itemCount,
        long totalAmount,
        Long paymentAmount
    ) {
        public static OrderSummaryResponse from(Order order) {
            return new OrderSummaryResponse(
                order.getId(),
                order.getStatus(),
                order.getItems().size(),
                order.getTotalAmount(),
                paymentAmountOf(order)
            );
        }
    }

    public record AdminOrderDetailResponse(
        Long id,
        Long buyerId,
        OrderStatus status,
        List<OrderItemResponse> items,
        long totalAmount,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        PaymentResponse payment
    ) {
        public static AdminOrderDetailResponse from(Order order) {
            return new AdminOrderDetailResponse(
                order.getId(),
                order.getBuyerId(),
                order.getStatus(),
                OrderItemResponse.from(order),
                order.getTotalAmount(),
                PaymentResponse.from(order)
            );
        }
    }

    public record AdminOrderSummaryResponse(
        Long id,
        Long buyerId,
        OrderStatus status,
        int itemCount,
        long totalAmount,
        Long paymentAmount
    ) {
        public static AdminOrderSummaryResponse from(Order order) {
            return new AdminOrderSummaryResponse(
                order.getId(),
                order.getBuyerId(),
                order.getStatus(),
                order.getItems().size(),
                order.getTotalAmount(),
                paymentAmountOf(order)
            );
        }
    }

    private static Long paymentAmountOf(Order order) {
        PaymentResult result = order.getPaymentResult();
        return result == null ? null : result.amount();
    }
}

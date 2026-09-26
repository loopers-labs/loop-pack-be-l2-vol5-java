package com.loopers.order.interfaces;

import com.loopers.interfaces.api.RequestFields;
import com.loopers.order.application.OrderUseCase;
import com.loopers.order.domain.Order;
import com.loopers.order.domain.OrderItem;
import com.loopers.order.domain.OrderStatus;
import com.loopers.order.domain.PaymentResult;

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

    public record PaymentResponse(Long amount, ZonedDateTime paidAt) {
        static PaymentResponse from(PaymentResult result) {
            if (result == null) {
                return null;
            }
            return new PaymentResponse(result.amount(), result.paidAt());
        }
    }

    public record OrderDetailResponse(
        Long id,
        OrderStatus status,
        List<OrderItemResponse> items,
        long totalAmount,
        PaymentResponse payment
    ) {
        public static OrderDetailResponse from(Order order) {
            return new OrderDetailResponse(
                order.getId(),
                order.getStatus(),
                OrderItemResponse.from(order),
                order.getTotalAmount(),
                PaymentResponse.from(order.getPaymentResult())
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
        PaymentResponse payment
    ) {
        public static AdminOrderDetailResponse from(Order order) {
            return new AdminOrderDetailResponse(
                order.getId(),
                order.getBuyerId(),
                order.getStatus(),
                OrderItemResponse.from(order),
                order.getTotalAmount(),
                PaymentResponse.from(order.getPaymentResult())
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

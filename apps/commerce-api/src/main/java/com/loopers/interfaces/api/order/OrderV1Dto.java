package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderCreateCommand;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.payment.PaymentConfirmCommand;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderQuantity;
import com.loopers.domain.payment.CardNumber;
import com.loopers.domain.payment.CardType;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentAttemptKey;

import java.time.Instant;
import java.util.List;

public class OrderV1Dto {

    public record PlaceRequest(List<Line> lines) {
        public PlaceRequest {
            if (lines == null || lines.isEmpty()) {
                throw new IllegalArgumentException("주문 품목은 1개 이상이어야 합니다.");
            }
        }

        public record Line(Long productId, Integer quantity) {
            public Line {
                if (productId == null) {
                    throw new IllegalArgumentException("productId 는 필수입니다.");
                }
                if (quantity == null) {
                    throw new IllegalArgumentException("quantity 는 필수입니다.");
                }
                OrderQuantity.of(quantity);
            }
        }

        public OrderCreateCommand toCommand(Long userId) {
            return new OrderCreateCommand(userId, lines.stream()
                .map(line -> new OrderCreateCommand.Line(line.productId(), OrderQuantity.of(line.quantity())))
                .toList());
        }
    }

    public record ConfirmRequest(String paymentAttemptKey, CardType cardType, String cardNo) {
        public ConfirmRequest {
            PaymentAttemptKey.of(paymentAttemptKey);
            if (cardType == null) {
                throw new IllegalArgumentException("cardType 은 필수입니다.");
            }
            CardNumber.of(cardNo);
        }

        public PaymentConfirmCommand toCommand(Long userId, Long orderId) {
            return new PaymentConfirmCommand(
                userId, orderId, PaymentAttemptKey.of(paymentAttemptKey), cardType, CardNumber.of(cardNo));
        }
    }

    public record ConfirmResponse(Long orderId, String orderStatus, String paymentStatus, String statusUrl) {
        public static ConfirmResponse of(Order order, Payment payment) {
            return new ConfirmResponse(
                order.getId(), order.getStatus().name(), payment.getStatus().name(),
                "/api/v1/orders/" + order.getId());
        }
    }

    public record ReconcileResponse(
        Long orderId,
        String orderStatus,
        String paymentStatus,
        String statusUrl,
        boolean lookupUnavailable
    ) {
        public static ReconcileResponse of(Order order, Payment payment, boolean lookupUnavailable) {
            return new ReconcileResponse(
                order.getId(), order.getStatus().name(), payment.getStatus().name(),
                "/api/v1/orders/" + order.getId(), lookupUnavailable);
        }
    }

    public record OrderItemResponse(Long productId, String productName, long unitPrice, int quantity, long lineTotal) {
        static OrderItemResponse from(OrderItem item) {
            return new OrderItemResponse(
                item.productId(), item.productName(), item.unitPrice().value(),
                item.quantity().value(), item.lineTotal().amount());
        }
    }

    public record OrderResponse(
        Long id,
        String status,
        long totalAmount,
        Long paidAmount,
        Instant placedAt,
        Instant confirmedAt,
        List<OrderItemResponse> items,
        String paymentMethod,
        String paymentStatus
    ) {
        public static OrderResponse from(Order order) {
            return of(order, null, null);
        }

        public static OrderResponse from(OrderFacade.OrderDetail detail) {
            OrderFacade.PaymentMethod method = detail.paymentMethod();
            return of(detail.order(), method != null ? method.name() : null,
                detail.payment() != null ? detail.payment().getStatus().name() : null);
        }

        private static OrderResponse of(Order order, String paymentMethod, String paymentStatus) {
            return new OrderResponse(
                order.getId(),
                order.getStatus().name(),
                order.getTotalAmount().amount(),
                order.getPaidAmount() != null ? order.getPaidAmount().amount() : null,
                order.getPlacedAt(),
                order.getConfirmedAt(),
                order.getItems().stream().map(OrderItemResponse::from).toList(),
                paymentMethod,
                paymentStatus);
        }
    }
}

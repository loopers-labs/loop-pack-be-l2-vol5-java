package com.loopers.interfaces.api.order;

import com.loopers.domain.common.Money;
import com.loopers.domain.order.OrderItemModel;
import com.loopers.domain.order.OrderModel;

import java.util.List;

public class OrderAdminV1Dto {

    /** 고객 품목 응답과 같은 값을 담지만, 관리자에게만 필요한 정보가 생겨도 고객 응답을 바꾸지 않도록 분리한다. */
    public record AdminOrderItemResponse(Long productId, Long quantity, Long unitPrice, Long amount) {
        public static AdminOrderItemResponse from(OrderItemModel item) {
            return new AdminOrderItemResponse(
                item.getProductId(),
                item.getQuantity(),
                item.getUnitPrice().toWon(),
                item.calculateAmount().toWon()
            );
        }
    }

    /** 고객 주문 응답에 구매자 식별자를 더한 형태다. */
    public record AdminOrderResponse(
        Long id,
        Long userId,
        String status,
        Long orderTotal,
        Long usedPointAmount,
        Long paymentAmount,
        List<AdminOrderItemResponse> items
    ) {
        public static AdminOrderResponse from(OrderModel order) {
            Money paymentAmount = order.getPaymentAmount();
            return new AdminOrderResponse(
                order.getId(),
                order.getUserId(),
                order.getStatus().name(),
                order.getOrderTotal().toWon(),
                order.getUsedPointAmount(),
                paymentAmount != null ? paymentAmount.toWon() : null,
                order.getItems().stream().map(AdminOrderItemResponse::from).toList()
            );
        }
    }
}

package com.loopers.application.order.dto;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderStatus;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * 주문 조회 결과. 품목은 지연 로딩이라 트랜잭션 안에서 변환해야 한다.
 * 결제 결과({@code payment})는 확정된 주문에만 있고 확정 전(DRAFT)에는 {@code null} 이다.
 * {@code orderedAt} 은 주문을 만든 시각이다.
 */
public record OrderInfo(
    Long id,
    Long userId,
    OrderStatus status,
    List<OrderItemInfo> items,
    long totalAmount,
    PaymentInfo payment,
    ZonedDateTime orderedAt
) {
    public static OrderInfo from(Order order) {
        return new OrderInfo(
            order.getId(),
            order.getUserId(),
            order.getStatus(),
            order.getItems().stream().map(OrderItemInfo::from).toList(),
            order.getTotalAmount().amount(),
            PaymentInfo.from(order.getPaymentResult()),
            order.getCreatedAt()
        );
    }
}

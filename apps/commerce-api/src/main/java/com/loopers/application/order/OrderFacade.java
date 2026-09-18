package com.loopers.application.order;

import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.OrderService.OrderRequestLine;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class OrderFacade {
    private final OrderService orderService;

    public OrderInfo createOrder(Long userId, List<OrderRequestLine> lines) {
        return OrderInfo.from(orderService.create(userId, lines));
    }

    public Page<OrderSummaryInfo> getMyOrders(Long userId, OrderStatus status, Pageable pageable) {
        return orderService.getOrderSummaries(userId, status, pageable).map(OrderSummaryInfo::from);
    }

    public OrderInfo getMyOrder(Long userId, Long orderId) {
        return OrderInfo.from(orderService.getMyOrder(userId, orderId));
    }

    public OrderInfo confirmOrder(Long userId, Long orderId) {
        return OrderInfo.from(orderService.confirm(userId, orderId));
    }
}

package com.loopers.application.order;

import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.user.UserErrorCode;
import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class AdminOrderFacade {
    private final OrderService orderService;
    private final UserService userService;

    /** 관리자 필터는 관리할 대상을 지정하므로, 없는 사용자면 USER_NOT_FOUND 로 알린다 (설계 D-24). */
    public Page<OrderSummaryInfo> getOrders(Long userId, OrderStatus status, Pageable pageable) {
        if (userId != null && !userService.exists(userId)) {
            throw new CoreException(UserErrorCode.USER_NOT_FOUND);
        }
        return orderService.getOrderSummaries(userId, status, pageable).map(OrderSummaryInfo::from);
    }

    public OrderInfo getOrder(Long orderId) {
        return OrderInfo.from(orderService.getOrder(orderId));
    }
}

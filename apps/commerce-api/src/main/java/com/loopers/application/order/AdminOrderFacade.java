package com.loopers.application.order;

import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.OrderSummary;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.payment.service.PaymentService;
import com.loopers.domain.user.UserErrorCode;
import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@RequiredArgsConstructor
@Component
public class AdminOrderFacade {
    private final OrderService orderService;
    private final UserService userService;
    private final PaymentService paymentService;

    /** 관리자 필터는 관리할 대상을 지정하므로, 없는 사용자면 USER_NOT_FOUND 로 알린다 (설계 D-24). */
    @Transactional(readOnly = true)
    public Page<OrderSummaryInfo> getOrders(Long userId, OrderStatus status, Pageable pageable) {
        if (userId != null && !userService.exists(userId)) {
            throw new CoreException(UserErrorCode.USER_NOT_FOUND);
        }
        Page<OrderSummary> summaries = orderService.getOrderSummaries(userId, status, pageable);
        Map<Long, Payment> payments = paymentService.findByOrderIds(summaries.map(OrderSummary::id).getContent());
        return summaries.map(summary -> OrderSummaryInfo.of(summary, payments));
    }

    @Transactional(readOnly = true)
    public OrderInfo getOrder(Long orderId) {
        Order order = orderService.getOrder(orderId);
        return OrderInfo.from(order, paymentService.findByOrderId(order.getId()).orElse(null));
    }
}

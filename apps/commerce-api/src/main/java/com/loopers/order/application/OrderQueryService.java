package com.loopers.order.application;

import com.loopers.order.application.port.in.OrderInfo;
import com.loopers.order.application.port.in.OrderSummaryInfo;
import com.loopers.order.application.port.out.OrderPort;
import com.loopers.order.domain.OrderModel;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객의 내 주문 조회 (ORD-06).
 */
@RequiredArgsConstructor
@Component
@Transactional(readOnly = true)
public class OrderQueryService {

    private final OrderPort orderPort;

    public Page<OrderSummaryInfo> getMyOrders(Long userId, Pageable pageable) {
        return orderPort.findAll(userId, pageable).map(OrderSummaryInfo::from);
    }

    /**
     * ORD-06: 내 주문만 조회한다. 없거나 남의 주문이면 존재를 드러내지 않고 404.
     */
    public OrderInfo getMyOrder(Long userId, Long orderId) {
        return OrderInfo.from(getOwnedOrder(userId, orderId));
    }

    private OrderModel getOwnedOrder(Long userId, Long orderId) {
        return orderPort.findById(orderId)
            .filter(order -> order.isOwnedBy(userId))
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[orderId = " + orderId + "] 주문을 찾을 수 없습니다."));
    }
}

package com.loopers.application.order;

import com.loopers.domain.order.OrderRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 주문 조회 유스케이스 (ORD-06). 모든 구매자의 주문을 본다.
 */
@RequiredArgsConstructor
@Component
public class OrderAdminFacade {

    private final OrderRepository orderRepository;

    @Transactional(readOnly = true)
    public Page<OrderSummaryInfo> getOrders(Long userId, Pageable pageable) {
        return orderRepository.findAll(userId, pageable).map(OrderSummaryInfo::from);
    }

    @Transactional(readOnly = true)
    public OrderInfo getOrder(Long orderId) {
        return orderRepository.findById(orderId)
            .map(OrderInfo::from)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[orderId = " + orderId + "] 주문을 찾을 수 없습니다."));
    }
}

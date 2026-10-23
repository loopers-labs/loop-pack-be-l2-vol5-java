package com.loopers.application.order;

import com.loopers.application.order.dto.OrderInfo;
import com.loopers.application.order.dto.OrderSummaryInfo;
import com.loopers.domain.order.OrderService;
import com.loopers.support.paging.PageRequests;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자용 주문 유스케이스. 구매자와 무관하게 주문을 조회한다.
 */
@RequiredArgsConstructor
@Component
public class AdminOrderFacade {

    private final OrderService orderService;

    /** 주문 목록을 최신순으로 조회한다. {@code userId} 를 주면 그 구매자의 주문만, 없는 구매자는 빈 목록이다. */
    @Transactional(readOnly = true)
    public Page<OrderSummaryInfo> getList(Long userId, int page, int size) {
        return orderService.getList(userId, PageRequests.of(page, size)).map(OrderSummaryInfo::from);
    }

    @Transactional(readOnly = true)
    public OrderInfo get(Long orderId) {
        return OrderInfo.from(orderService.get(orderId));
    }
}

package com.loopers.application.order;

import com.loopers.domain.order.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

// 관리자 전용 — 전체 목록/상세 조회만 한다(소유권 검사 없음). 변경(생성·확정)은 고객만 하므로
// OrderFacade에 있다 (docs/week2/design.md 3번 섹션 "Facade 경계" 참고).
@RequiredArgsConstructor
@Component
public class OrderAdminFacade {

    private final OrderService orderService;

    public List<OrderInfo> getOrders() {
        return orderService.getOrders().stream()
            .map(OrderInfo::from)
            .toList();
    }

    public OrderInfo getOrder(Long orderId) {
        return OrderInfo.from(orderService.getOrder(orderId));
    }
}

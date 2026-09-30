package com.loopers.domain.order;

import java.util.List;
import java.util.Optional;

public interface OrderRepository {
    Optional<Order> find(Long id);

    /**
     * 비관적 락으로 조회한다 — 같은 주문에 확정 요청이 겹칠 때(더블클릭·재시도) 중복 반영을 막는다.
     * (docs/week2/design.md 5번 섹션 "Order 자신의 DRAFT→CONFIRMED 전이도 같은 방식으로 보호한다" 참고)
     */
    Optional<Order> findForUpdate(Long id);

    List<Order> findAllByUserId(Long userId);

    List<Order> findAll();

    Order save(Order order);
}

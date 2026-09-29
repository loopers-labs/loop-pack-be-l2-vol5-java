package com.loopers.domain.order;

import java.util.List;
import java.util.Optional;

public interface OrderRepository {
    Order save(Order order);

    Optional<Order> findById(Long id);

    List<Order> findAllByUserId(Long userId);

    /**
     * 관리자 조회용. userId 가 null 이면 전체 주문을 최신순으로 반환한다.
     */
    List<Order> findAllForAdmin(Long userId, int page, int size);
}

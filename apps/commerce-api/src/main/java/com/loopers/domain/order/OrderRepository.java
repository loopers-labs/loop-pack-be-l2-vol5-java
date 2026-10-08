package com.loopers.domain.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface OrderRepository {
    Optional<Order> findById(long id);

    /**
     * 신규 주문을 저장하거나 객체가 가진 전체 상태를 반영한다.
     * 주문 확정에는 DRAFT 조건을 검사하는 confirmIfDraft를 사용한다.
     */
    Order save(Order order);

    /**
     * confirm()을 마친 주문의 상태·결제 정보를, DB가 DRAFT일 때만 저장한다.
     * 조건을 만족하지 않으면 INVALID_REQUEST로 거절한다.
     */
    void confirmIfDraft(Order order);

    List<Order> findByUserId(long userId);

    Page<Order> findAll(Long userId, Pageable pageable);
}

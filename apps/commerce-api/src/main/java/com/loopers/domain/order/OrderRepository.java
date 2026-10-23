package com.loopers.domain.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface OrderRepository {
    Order save(Order order);

    /** 주문자가 {@code userId} 인 주문만 조회한다. 다른 사용자의 주문은 없는 주문과 구분하지 않는다. */
    Optional<Order> findByIdAndUserId(Long id, Long userId);

    /**
     * 주문자가 {@code userId} 인 주문을 배타 잠금(FOR UPDATE)으로 조회한다. 같은 주문을 동시에 확정하는 요청은 먼저 잡은
     * 트랜잭션이 끝날 때까지 기다린다. 반드시 Facade 의 트랜잭션 안에서, 이 주문을 처음 읽는 조회로 호출한다.
     */
    Optional<Order> findByIdAndUserIdForUpdate(Long id, Long userId);

    /**
     * 주문자와 무관하게 id 로 배타 잠금(FOR UPDATE)하며 조회한다. 결제 결과를 적용하는 트랜잭션이 쓴다. 결과는 요청자가 없는
     * 입구(PG 알림)에서도 들어오므로 소유권은 호출 전에 확인한다. 반드시 트랜잭션 안에서, 이 주문을 처음 읽는 조회로 호출한다.
     */
    Optional<Order> findByIdForUpdate(Long id);

    /** 주문자와 무관하게 id 로 조회한다. 관리자 조회용이다. */
    Optional<Order> findById(Long id);

    /**
     * 주문 목록을 최신순(id 내림차순)으로 조회한다. {@code userId} 를 지정하면 그 구매자의 주문만, {@code null} 이면
     * 모든 주문을 조회한다. 주문이 없는 구매자는 빈 결과다. 정렬은 Pageable 이 아니라 구현이 고정한다.
     */
    Page<Order> findAll(Long userId, Pageable pageable);
}

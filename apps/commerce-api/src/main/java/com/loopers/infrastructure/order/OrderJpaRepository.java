package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderModel;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 저장과 식별자 조회 같은 기본 동작만 맡는다. 조건이 붙는 조회는 OrderRepositoryImpl이 QueryDSL로 쓴다.
 */
public interface OrderJpaRepository extends JpaRepository<OrderModel, Long> {
}

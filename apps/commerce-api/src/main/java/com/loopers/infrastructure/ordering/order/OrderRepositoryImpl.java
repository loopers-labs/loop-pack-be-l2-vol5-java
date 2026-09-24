package com.loopers.infrastructure.ordering.order;

import com.loopers.domain.ordering.order.Order;
import com.loopers.domain.ordering.order.OrderRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
// 주문 저장소 JPA 구현체
public class OrderRepositoryImpl implements OrderRepository {
    private final OrderJpaRepository orderJpaRepository;
    private final OrderEntityMapper mapper;

    // 신규 저장 또는 기존 엔티티 갱신
    @Override
    public Order save(Order order) {
        OrderJpaEntity entity;
        if (order.getId() == null) {
            entity = mapper.toNewEntity(order);
        } else {
            entity = orderJpaRepository.findById(order.getId()).orElseThrow();
            mapper.apply(order, entity);
        }
        return mapper.toDomain(orderJpaRepository.save(entity));
    }

    // ID로 주문 조회
    @Override
    public Optional<Order> findById(long orderId) {
        return orderJpaRepository.findById(orderId).map(mapper::toDomain);
    }

    // 비관적 쓰기 잠금으로 조회
    @Override
    public Optional<Order> findByIdForUpdate(long orderId) {
        return orderJpaRepository.findByIdForUpdate(orderId).map(mapper::toDomain);
    }
}

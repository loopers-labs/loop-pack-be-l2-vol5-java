package com.loopers.infrastructure.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.List;

@RequiredArgsConstructor
@Component
public class OrderRepositoryImpl implements OrderRepository {
    private final OrderJpaRepository orderJpaRepository;

    @Override
    public Order save(Order order) {
        OrderJpaEntity entity = order.getId() == null
            ? OrderJpaMapper.toNewEntity(order)
            : orderJpaRepository.findById(order.getId()).orElseThrow(
                () -> new IllegalArgumentException("Order does not exist: " + order.getId())
            );
        if (order.getId() != null) {
            OrderJpaMapper.update(order, entity);
        }
        return OrderJpaMapper.toDomain(orderJpaRepository.save(entity));
    }

    @Override
    public Optional<Order> findById(Long orderId) {
        return orderJpaRepository.findById(orderId).map(OrderJpaMapper::toDomain);
    }

    @Override
    public List<Order> findAllByUserId(Long userId) {
        return orderJpaRepository.findAllByUserId(userId).stream().map(OrderJpaMapper::toDomain).toList();
    }

    @Override
    public List<Order> findAll() {
        return orderJpaRepository.findAll().stream().map(OrderJpaMapper::toDomain).toList();
    }
}

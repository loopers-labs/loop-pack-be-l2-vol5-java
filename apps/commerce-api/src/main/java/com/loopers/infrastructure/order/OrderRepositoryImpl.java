package com.loopers.infrastructure.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class OrderRepositoryImpl implements OrderRepository {
    private final OrderJpaRepository orderJpaRepository;

    @Override
    public Order save(Order order) {
        return orderJpaRepository.save(order);
    }

    @Override
    public Optional<Order> findByIdAndUserId(Long orderId, Long userId) {
        return orderJpaRepository.findWithItemsByIdAndUserId(orderId, userId);
    }

    @Override
    public Optional<Order> findById(Long orderId) {
        return orderJpaRepository.findWithItemsById(orderId);
    }

    @Override
    public Page<Order> findPage(Long userId, OrderStatus status, Pageable pageable) {
        return orderJpaRepository.findPage(userId, status, pageable);
    }
}

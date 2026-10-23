package com.loopers.infrastructure.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
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
    public Optional<Order> findByIdAndUserIdForUpdate(Long id, Long userId) {
        return orderJpaRepository.findByIdAndUserIdForUpdate(id, userId);
    }

    @Override
    public Optional<Order> findByIdForUpdate(Long id) {
        return orderJpaRepository.findByIdForUpdate(id);
    }

    @Override
    public Optional<Order> findByIdAndUserId(Long id, Long userId) {
        return orderJpaRepository.findByIdAndUserId(id, userId);
    }

    @Override
    public Optional<Order> findById(Long id) {
        return orderJpaRepository.findById(id);
    }

    @Override
    public Page<Order> findAll(Long userId, Pageable pageable) {
        return orderJpaRepository.findAllByUser(userId, pageable);
    }
}

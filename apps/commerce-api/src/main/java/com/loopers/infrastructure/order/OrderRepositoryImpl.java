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
    public Order save(Order order) { return orderJpaRepository.save(order); }

    @Override
    public Optional<Order> findById(Long orderId) { return orderJpaRepository.findById(orderId); }

    @Override
    public List<Order> findAllByUserId(Long userId) { return orderJpaRepository.findAllByUserId(userId); }

    @Override
    public List<Order> findAll() { return orderJpaRepository.findAll(); }
}

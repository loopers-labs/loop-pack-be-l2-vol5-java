package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class OrderRepositoryImpl implements OrderRepository {

    private final OrderJpaRepository orderJpaRepository;

    @Override
    public OrderModel save(OrderModel order) {
        return orderJpaRepository.save(order);
    }

    @Override
    public Optional<OrderModel> find(Long id) {
        return orderJpaRepository.findById(id);
    }

    @Override
    public Optional<OrderModel> findForUpdate(Long id) {
        return orderJpaRepository.findForUpdate(id);
    }

    @Override
    public List<OrderModel> findByUserId(Long userId) {
        return orderJpaRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Override
    public List<OrderModel> findAll() {
        return orderJpaRepository.findAllByOrderByCreatedAtDesc();
    }
}

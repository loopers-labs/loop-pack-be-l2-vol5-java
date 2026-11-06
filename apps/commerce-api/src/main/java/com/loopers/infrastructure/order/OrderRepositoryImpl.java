package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class OrderRepositoryImpl implements OrderRepository {

    private final OrderJpaRepository orderJpaRepository;

    @Override
    public Optional<OrderModel> findById(Long id) {
        return orderJpaRepository.findById(id);
    }

    @Override
    public Optional<OrderModel> findByIdWithLock(Long id) {
        return orderJpaRepository.findByIdWithLock(id);
    }

    @Override
    public List<OrderModel> findAllByUserId(Long userId) {
        return orderJpaRepository.findAllByUserId(userId);
    }

    @Override
    public Page<OrderModel> findAll(Pageable pageable) {
        return orderJpaRepository.findAll(pageable);
    }

    @Override
    public List<OrderModel> findExpiredDrafts(ZonedDateTime now) {
        return orderJpaRepository.findAllByStatusAndExpiredAtBefore(OrderStatus.DRAFT, now);
    }

    @Override
    public List<OrderModel> findByStatus(OrderStatus status) {
        return orderJpaRepository.findAllByStatus(status);
    }

    @Override
    public OrderModel save(OrderModel order) {
        return orderJpaRepository.save(order);
    }

    @Override
    public void saveAll(List<OrderModel> orders) {
        orderJpaRepository.saveAll(orders);
    }
}

package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderModel;
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

    /**
     * 즉시 flush해 품목 식별자와 생성·수정 시각이 응답을 만들기 전에 반영되게 한다.
     */
    @Override
    public OrderModel save(OrderModel order) {
        return orderJpaRepository.saveAndFlush(order);
    }

    @Override
    public Optional<OrderModel> findById(Long orderId) {
        return orderJpaRepository.findById(orderId);
    }

    @Override
    public Page<OrderModel> findAll(Long userId, Pageable pageable) {
        if (userId == null) {
            return orderJpaRepository.findAll(pageable);
        }
        return orderJpaRepository.findAllByUserId(userId, pageable);
    }
}

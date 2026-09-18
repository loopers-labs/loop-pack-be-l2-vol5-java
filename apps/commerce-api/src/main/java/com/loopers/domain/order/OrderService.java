package com.loopers.domain.order;

import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;

    public Order place(Order order) {
        return orderRepository.save(order);
    }

    public Order getOwned(Long userId, Long orderId) {
        return orderRepository.findById(orderId)
            .filter(order -> order.isOwnedBy(userId))
            .orElseThrow(() -> new DomainException(DomainError.ORDER_NOT_FOUND));
    }

    public List<Order> findMine(Long userId) {
        return orderRepository.findByUserId(userId);
    }

    public Order get(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow(() -> new DomainException(DomainError.ORDER_NOT_FOUND));
    }

    public List<Order> findPage(Long userId, OrderStatus status, int offset, int limit) {
        return orderRepository.findPage(userId, status, offset, limit);
    }
}

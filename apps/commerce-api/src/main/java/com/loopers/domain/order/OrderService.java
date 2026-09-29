package com.loopers.domain.order;

import com.loopers.domain.common.PageNumber;
import com.loopers.domain.common.PageSize;
import com.loopers.domain.common.PageWindow;
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

    public PageWindow<Order> findPage(Long userId, OrderStatus status, PageNumber page, PageSize size) {
        return orderRepository.findPage(userId, status, page, size);
    }
}

package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Component
public class OrderService {

    private final OrderRepository orderRepository;

    @Transactional
    public OrderModel create(Long userId, List<OrderItemModel> items) {
        return orderRepository.save(new OrderModel(userId, items));
    }

    @Transactional(readOnly = true)
    public OrderModel getMyOrder(Long userId, Long orderId) {
        return orderRepository.findById(orderId)
            .filter(order -> order.getUserId().equals(userId))
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + orderId + "] 주문을 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public List<OrderModel> getMyOrders(Long userId) {
        return orderRepository.findAllByUserId(userId);
    }

    @Transactional
    public OrderModel save(OrderModel order) {
        return orderRepository.save(order);
    }
}

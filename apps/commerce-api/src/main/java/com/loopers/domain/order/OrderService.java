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
    public OrderModel create(Long userId, List<OrderItem> items) {
        return orderRepository.save(new OrderModel(userId, items));
    }

    @Transactional(readOnly = true)
    public OrderModel get(Long id) {
        return orderRepository.find(id)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다."));
    }

    @Transactional
    public OrderModel getForUpdate(Long id) {
        return orderRepository.findForUpdate(id)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public List<OrderModel> getByUser(Long userId) {
        return orderRepository.findByUserId(userId);
    }

    @Transactional(readOnly = true)
    public List<OrderModel> getAll() {
        return orderRepository.findAll();
    }

    @Transactional
    public OrderModel save(OrderModel order) {
        return orderRepository.save(order);
    }
}

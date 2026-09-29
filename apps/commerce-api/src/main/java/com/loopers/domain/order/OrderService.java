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
    public Order createOrder(Long userId, List<OrderItem> items) {
        return orderRepository.save(new Order(userId, items));
    }

    @Transactional(readOnly = true)
    public Order getOrder(Long orderId) {
        return orderRepository.findById(orderId)
            .orElseThrow(() -> new CoreException(
                ErrorType.NOT_FOUND, "[id = " + orderId + "] 주문을 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public List<Order> getOrders(Long userId) {
        return orderRepository.findAllByUserId(userId);
    }

    /**
     * 관리자 조회. userId 가 null 이면 전체 주문을 최신순으로 반환한다.
     */
    @Transactional(readOnly = true)
    public List<Order> getOrdersForAdmin(Long userId, int page, int size) {
        return orderRepository.findAllForAdmin(userId, page, size);
    }

    /**
     * 확정에 실패하면 예외만 던지고 저장하지 않아 주문은 DRAFT 로 남는다.
     */
    @Transactional
    public Order confirmOrder(Order order) {
        order.confirm();
        return orderRepository.save(order);
    }
}

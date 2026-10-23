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
     * 품목까지 함께 읽는다. 트랜잭션 밖에서 응답을 만드는 confirm 경로가 쓴다.
     */
    @Transactional(readOnly = true)
    public Order getOrderWithItems(Long orderId) {
        return orderRepository.findByIdWithItems(orderId)
            .orElseThrow(() -> new CoreException(
                ErrorType.NOT_FOUND, "[id = " + orderId + "] 주문을 찾을 수 없습니다."));
    }
}

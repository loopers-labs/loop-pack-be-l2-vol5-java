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
     * DRAFT 인 본인 주문만 조건부 UPDATE 로 확정한다. 영향 행이 0 이면 이미 확정된 주문이다.
     * 결제액은 저장된 품목의 단가 × 수량 합이며, 응답에 쓸 수 있도록 갱신 뒤 DB 상태를 다시 읽은 주문을 반환한다.
     */
    @Transactional
    public Order confirmOrder(Order order) {
        int updated = orderRepository.confirmIfDraft(order.getId(), order.getUserId(), order.getTotalAmount());
        if (updated == 0) {
            throw new CoreException(ErrorType.CONFLICT, "이미 확정된 주문입니다.");
        }
        return orderRepository.reload(order);
    }
}

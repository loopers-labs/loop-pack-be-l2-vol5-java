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
    public Order createOrder(Long userId, List<Order.OrderItemDraft> drafts) {
        Order order = new Order(userId, drafts);
        return orderRepository.save(order);
    }

    /**
     * 소유자 검증 — 주문이 없거나 요청자가 소유자가 아니면 같은 응답(404, 같은 메시지)으로 합친다.
     * Week1 INV-001과 동일한 원칙: 응답 차이로 "남의 주문이 존재한다"는 사실을 노출하지 않는다.
     */
    @Transactional(readOnly = true)
    public Order getOwnedOrder(Long orderId, Long requesterId) {
        Order order = orderRepository.find(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + orderId + "] 주문을 찾을 수 없습니다."));
        if (!order.getUserId().equals(requesterId)) {
            throw new CoreException(ErrorType.NOT_FOUND, "[id = " + orderId + "] 주문을 찾을 수 없습니다.");
        }
        return order;
    }

    /**
     * 확정 전용 — 비관적 락으로 조회해 같은 주문에 대한 동시 확정 요청을 직렬화한다.
     * (docs/week2/design.md 5번 섹션 참고). 단순 조회(GET)는 락이 필요 없어 getOwnedOrder를 그대로 쓴다.
     */
    @Transactional
    public Order getOwnedOrderForUpdate(Long orderId, Long requesterId) {
        Order order = orderRepository.findForUpdate(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + orderId + "] 주문을 찾을 수 없습니다."));
        if (!order.getUserId().equals(requesterId)) {
            throw new CoreException(ErrorType.NOT_FOUND, "[id = " + orderId + "] 주문을 찾을 수 없습니다.");
        }
        return order;
    }

    @Transactional(readOnly = true)
    public List<Order> getMyOrders(Long userId) {
        return orderRepository.findAllByUserId(userId);
    }

    @Transactional(readOnly = true)
    public Order getOrder(Long orderId) {
        return orderRepository.find(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + orderId + "] 주문을 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public List<Order> getOrders() {
        return orderRepository.findAll();
    }

    @Transactional
    public Order save(Order order) {
        return orderRepository.save(order);
    }
}

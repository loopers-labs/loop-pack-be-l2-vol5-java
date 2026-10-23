package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.Map;

@RequiredArgsConstructor
@Component
public class OrderService {

    private final OrderRepository orderRepository;

    /** DRAFT 주문을 만들어 저장한다. 재고와 포인트는 건드리지 않는다. */
    public Order create(Long userId, OrderLines lines, Map<Long, OrderProduct> products) {
        return orderRepository.save(Order.create(userId, lines, products));
    }

    /** 주문자와 무관하게 id 로 조회한다. 관리자 조회용이며 없으면 {@code NOT_FOUND} 다. */
    public Order get(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow(OrderService::notFound);
    }

    /** 주문 목록을 최신순으로 조회한다. {@code userId} 가 {@code null} 이면 모든 주문이다. */
    public Page<Order> getList(Long userId, Pageable pageable) {
        return orderRepository.findAll(userId, pageable);
    }

    /**
     * 본인의 주문을 조회한다. 없는 주문과 다른 사용자의 주문은 같은 {@code NOT_FOUND} 로 거절해,
     * 순차 id 로 다른 사람의 주문이 존재하는지 알아내지 못하게 한다.
     */
    public Order getMine(Long orderId, Long userId) {
        return orderRepository.findByIdAndUserId(orderId, userId).orElseThrow(OrderService::notFound);
    }

    /**
     * 본인의 주문을 잠그고 조회한다. 없음·타인의 주문 처리는 {@link #getMine} 과 같다. 주문 상태를 바꾸는 유스케이스(확정)의
     * 첫 주문 조회로 쓴다.
     */
    public Order getMineForUpdate(Long orderId, Long userId) {
        return orderRepository.findByIdAndUserIdForUpdate(orderId, userId).orElseThrow(OrderService::notFound);
    }

    /**
     * 주문자와 무관하게 주문을 잠그고 조회한다. 없으면 {@code NOT_FOUND} 다. 결제 결과 적용이 첫 주문 조회로 쓴다. 소유권은
     * 확인하지 않으므로 요청자가 있는 입구는 먼저 {@link #getMine} 류로 확인한다.
     */
    public Order getForUpdate(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId).orElseThrow(OrderService::notFound);
    }

    private static CoreException notFound() {
        return new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다.");
    }
}

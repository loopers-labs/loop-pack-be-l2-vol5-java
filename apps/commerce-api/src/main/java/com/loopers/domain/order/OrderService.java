package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Component
public class OrderService {

    private final OrderRepository orderRepository;

    @Transactional(readOnly = true)
    public OrderModel getOrder(Long id, Long userId) {
        return orderRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 주문을 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public Page<OrderModel> getOrders(Long userId, Pageable pageable) {
        return orderRepository.findAllByUserId(userId, pageable);
    }

    @Transactional(readOnly = true)
    public OrderModel getOrderForAdmin(Long id) {
        return orderRepository.findById(id)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 주문을 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public Page<OrderModel> getOrdersForAdmin(Pageable pageable) {
        return orderRepository.findAll(pageable);
    }

    @Transactional
    public OrderModel createOrder(Long userId, List<OrderItem> items) {
        return orderRepository.save(new OrderModel(userId, items));
    }

    // 상태와 상관없이 본인 주문을 잠근다. 결제 시도가 이미 있는 주문의 같은 요청을 알아보려면 DRAFT가 아니어도 읽어야 한다
    @Transactional
    public OrderModel getOrderOwnedByForUpdate(Long id, Long userId) {
        return orderRepository.findByIdAndUserIdForUpdate(id, userId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 주문을 찾을 수 없습니다."));
    }

    // 소유자를 모르는 경로(callback)의 결과 적용에서 쓴다. 소유권은 호출 전에 이미 확인했다
    @Transactional
    public OrderModel getOrderForUpdate(Long id) {
        return orderRepository.findByIdForUpdate(id)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 주문을 찾을 수 없습니다."));
    }

    // 확정할 주문을 잠근 뒤에 DRAFT인지 확인한다
    @Transactional
    public OrderModel getDraftOrderOwnedBy(Long id, Long userId) {
        return orderRepository.findByIdAndUserIdForUpdate(id, userId)
            .filter(order -> order.getStatus() == OrderStatus.DRAFT)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 확정 가능한 주문을 찾을 수 없습니다."));
    }

    @Transactional
    public void confirmOrder(OrderModel order, Long paidAmount) {
        order.confirm(paidAmount);
        orderRepository.save(order);
    }
}

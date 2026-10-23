package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;

@RequiredArgsConstructor
@Component
public class OrderService {

    private final OrderRepository orderRepository;
    /** 만료 · 결제 시각의 기준. 시각을 정하는 곳을 이 서비스 한 곳에 둠 (설계 2.3) */
    private final Clock clock;

    /** 스냅샷을 담은 품목으로 DRAFT 를 저장한다. 만료 시각은 지금 + 30분 (ORD-08). 차감하지 않는다. */
    @Transactional
    public Order create(Long userId, List<OrderLine> lines) {
        return orderRepository.save(Order.draft(userId, lines, ZonedDateTime.now(clock)));
    }

    /** 타인의 주문은 없는 주문과 같음 (ORD-06) */
    @Transactional
    public Order getMyOrderForUpdate(Long userId, Long orderId) {
        return orderRepository.findByIdAndUserIdForUpdate(orderId, userId)
            .orElseThrow(() -> new CoreException(OrderErrorCode.ORDER_NOT_FOUND));
    }

    @Transactional
    public Order getOrderForUpdate(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId)
            .orElseThrow(() -> new CoreException(OrderErrorCode.ORDER_NOT_FOUND));
    }

    /** 상품을 다루기 전에 부름 (ORD-07, ORD-08) */
    public void validateConfirmable(Order order) {
        order.validateConfirmable(ZonedDateTime.now(clock));
    }

    @Transactional
    public void startPayment(Order order) {
        order.startPayment(ZonedDateTime.now(clock));
    }

    @Transactional
    public void confirmWithoutPayment(Order order) {
        order.confirmWithoutPayment(ZonedDateTime.now(clock));
    }

    /** 결제 시각은 PG 승인 시각이 아니라 내부 반영 시각임 */
    @Transactional
    public void confirmPaid(Order order, long paymentAmount) {
        order.confirmPaid(paymentAmount, ZonedDateTime.now(clock));
    }

    @Transactional
    public void failPayment(Order order) {
        order.failPayment();
    }

    /** 요청자 본인의 주문만 조회한다. 타인의 주문은 없는 주문과 같다 (설계 6.1). */
    @Transactional(readOnly = true)
    public Order getMyOrder(Long userId, Long orderId) {
        return orderRepository.findByIdAndUserId(orderId, userId)
            .orElseThrow(() -> new CoreException(OrderErrorCode.ORDER_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public Order getOrder(Long orderId) {
        return orderRepository.findById(orderId)
            .orElseThrow(() -> new CoreException(OrderErrorCode.ORDER_NOT_FOUND));
    }

    /** 품목을 읽는 요약은 트랜잭션 안에서 만든다 (open-in-view 가 꺼져 있다). */
    @Transactional(readOnly = true)
    public Page<OrderSummary> getOrderSummaries(Long userId, OrderStatus status, Pageable pageable) {
        return orderRepository.findPage(userId, status, pageable).map(OrderSummary::from);
    }
}

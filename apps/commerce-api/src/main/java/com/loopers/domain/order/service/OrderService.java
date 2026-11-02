package com.loopers.domain.order.service;

import com.loopers.domain.order.entity.Order;
import com.loopers.domain.order.enums.OrderErrorCode;
import com.loopers.domain.order.enums.OrderStatus;
import com.loopers.domain.order.repository.OrderRepository;
import com.loopers.domain.order.vo.OrderConfirmed;
import com.loopers.domain.order.vo.OrderLine;
import com.loopers.domain.order.vo.OrderSummary;
import com.loopers.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
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
    /** 시각을 정하는 곳을 한 곳에 둠 (설계 2.3) */
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

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

    /** 확정 사실은 상태 전이 바로 옆에서 냄. 보내는 시점(commit 뒤)은 listener 가 정함 (6주차 D-70, D-72) */
    @Transactional
    public void confirmWithoutPayment(Order order) {
        order.confirmWithoutPayment(ZonedDateTime.now(clock));
        eventPublisher.publishEvent(OrderConfirmed.from(order));
    }

    /** 결제 시각은 PG 승인 시각이 아니라 내부 반영 시각임. 결제 대기에서만 통과하므로 반복 적용은 확정 사실을 다시 내지 않음 */
    @Transactional
    public void confirmPaid(Order order, long paymentAmount) {
        order.confirmPaid(paymentAmount, ZonedDateTime.now(clock));
        eventPublisher.publishEvent(OrderConfirmed.from(order));
    }

    @Transactional
    public void failPayment(Order order) {
        order.failPayment();
    }

    /** 타인의 주문은 없는 주문과 같음 (설계 6.1) */
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

    /** open-in-view 가 꺼져 있어 품목을 읽는 요약은 트랜잭션 안에서 만듦 */
    @Transactional(readOnly = true)
    public Page<OrderSummary> getOrderSummaries(Long userId, OrderStatus status, Pageable pageable) {
        return orderRepository.findPage(userId, status, pageable).map(OrderSummary::from);
    }
}

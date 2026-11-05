package com.loopers.domain.order.service;

import com.loopers.domain.order.entity.Order;
import com.loopers.domain.order.entity.OrderConfirmedOutbox;
import com.loopers.domain.order.enums.OrderBackfillResult;
import com.loopers.domain.order.enums.OrderErrorCode;
import com.loopers.domain.order.enums.OrderStatus;
import com.loopers.domain.order.gateway.OrderConfirmedSerializer;
import com.loopers.domain.order.repository.OrderConfirmedOutboxRepository;
import com.loopers.domain.order.repository.OrderRepository;
import com.loopers.domain.order.vo.OrderConfirmed;
import com.loopers.domain.order.vo.OrderLine;
import com.loopers.domain.order.vo.OrderSummary;
import com.loopers.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class OrderService {

    private final OrderRepository orderRepository;
    /** 시각을 정하는 곳을 한 곳에 둠 (설계 2.3) */
    private final Clock clock;
    private final OrderConfirmedOutboxRepository outboxRepository;
    private final OrderConfirmedSerializer serializer;

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

    /** 확정 사실은 상태 전이 바로 옆에서 남김 (6주차 D-70, 7주차 D-101) */
    @Transactional
    public void confirmWithoutPayment(Order order) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        order.confirmWithoutPayment(now);
        recordConfirmed(order, now);
    }

    /** 결제 시각은 PG 승인 시각이 아니라 내부 반영 시각임. 결제 대기에서만 통과하므로 반복 적용은 확정 사실을 다시 남기지 않음 */
    @Transactional
    public void confirmPaid(Order order, long paymentAmount) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        order.confirmPaid(paymentAmount, now);
        recordConfirmed(order, now);
    }

    /** 확정과 같은 TX 에 남겨 commit 되면 relay 가 반드시 찾게 함 (7주차 D-101) */
    private void recordConfirmed(Order order, ZonedDateTime now) {
        OrderConfirmed event = OrderConfirmed.from(order);
        outboxRepository.save(OrderConfirmedOutbox.pending(event, serializer.serialize(event), now));
    }

    /**
     * Outbox 이전에 확정됐지만 발행되지 못한 주문을 잇는 복구. 확정 · 결제 · 예약은 건드리지 않음 (7주차 D-112).
     * identity 는 저장된 사실에서 다시 유도하므로 6주차가 보냈을 메시지와 같은 사건이 됨
     */
    @Transactional
    public OrderBackfillResult backfillConfirmed(Long orderId) {
        Order order = getOrder(orderId);
        if (order.getStatus() != OrderStatus.CONFIRMED || order.getConfirmedAt() == null) {
            throw new IllegalStateException("확정 사실이 없는 주문은 잇지 않음. orderId=" + orderId + " status=" + order.getStatus());
        }
        OrderConfirmed event = OrderConfirmed.from(order);
        String payload = serializer.serialize(event);
        Optional<OrderConfirmedOutbox> recorded = outboxRepository.findByOrderId(orderId);
        if (recorded.isEmpty()) {
            outboxRepository.save(OrderConfirmedOutbox.pending(event, payload, ZonedDateTime.now(clock)));
            return OrderBackfillResult.RECORDED;
        }
        // payload 에 eventId · confirmedAt · items 가 모두 담겨 있어 한 번에 비교함
        if (!recorded.get().getPayload().equals(payload)) {
            throw new IllegalStateException("저장된 Outbox 와 주문의 확정 사실이 다름. orderId=" + orderId + " eventId=" + recorded.get().getEventId());
        }
        return OrderBackfillResult.ALREADY_RECORDED;
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

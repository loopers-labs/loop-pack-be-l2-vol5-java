package com.loopers.application.order;

import com.loopers.domain.order.OrderEventOutboxModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * Outbox 이전(W6 직접 발행)에 확정됐지만 broker 로 나가지 못한 주문을 Outbox 로 잇는다. 운영자가 주문을 지정한다: 이전 확정 주문 전체를 소급하지 않는다.
 * 주문의 저장된 확정 사실(eventId·confirmedAt·품목)로 확정 트랜잭션과 같은 행을 만든다 (OrderConfirmedOutboxFactory). PG 를 부르지 않고, 주문·재고를 바꾸지 않는다.
 * 행이 없으면 PENDING 으로 넣고, 있으면 내용이 같은지만 본다. 확정 사실이 없거나 내용이 다르면 추측하지 않고 예외로 멈춘다.
 * 보내는 것은 relay 다. 이미 소비된 사건이 다시 나가도 소비자의 완료 기록(eventId)이 막는다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class OrderConfirmedBackfill {
    private final OrderService orderService;
    private final OrderConfirmedOutboxFactory outboxFactory;

    public enum Result {
        /** Outbox 가 없어 새로 넣었다. */
        RECORDED,
        /** 같은 내용의 Outbox 가 이미 있다. 아무것도 바꾸지 않았다. */
        ALREADY_RECORDED
    }

    /** 주문 하나. 주문 행을 잠가 확정 트랜잭션·다른 복구와 겹치지 않는다. 없으면 ER-05 ORDER_NOT_FOUND. */
    @Transactional
    public Result backfill(Long orderId) {
        OrderModel order = orderService.getForUpdate(orderId);
        if (order.getStatus() != OrderStatus.CONFIRMED || order.getConfirmedAt() == null) {
            throw new IllegalStateException("확정 사실이 없어 Outbox 를 만들지 않는다 [orderId = " + orderId + ", status = " + order.getStatus()
                + ", confirmedAt = " + order.getConfirmedAt() + "]");
        }
        OrderEventOutboxModel expected = outboxFactory.create(order);
        Optional<OrderEventOutboxModel> existing = orderService.findOutbox(expected.getEventId());
        if (existing.isPresent()) {
            ensureSameEvent(existing.get(), expected);
            log.info("Outbox 복구: 이미 있음 [orderId = {}, eventId = {}, status = {}]", orderId, expected.getEventId(), existing.get().getStatus());
            return Result.ALREADY_RECORDED;
        }
        orderService.appendOutbox(expected);
        log.warn("Outbox 복구: 누락된 확정을 PENDING 으로 넣음 [orderId = {}, eventId = {}, confirmedAt = {}]",
            orderId, expected.getEventId(), order.getConfirmedAt().toInstant());
        return Result.RECORDED;
    }

    private static void ensureSameEvent(OrderEventOutboxModel existing, OrderEventOutboxModel expected) {
        boolean same = Objects.equals(existing.getOrderId(), expected.getOrderId())
            && Objects.equals(existing.getEventType(), expected.getEventType())
            && Objects.equals(existing.getSchemaVersion(), expected.getSchemaVersion())
            && Objects.equals(existing.getMessageKey(), expected.getMessageKey())
            && Objects.equals(existing.getPayload(), expected.getPayload());
        if (!same) {
            throw new IllegalStateException("같은 eventId 의 Outbox 가 저장된 확정 사실과 다르다. 고치지 않고 멈춘다 [eventId = " + expected.getEventId()
                + ", outboxId = " + existing.getId() + "]");
        }
    }
}

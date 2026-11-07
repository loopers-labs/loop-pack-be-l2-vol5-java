package com.loopers.application.order;

import com.loopers.domain.order.OrderEventOutboxModel;
import com.loopers.domain.order.OrderModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * CONFIRMED 주문의 저장된 확정 사실(eventId·confirmedAt·품목)로 OrderConfirmed Outbox 행을 만든다. 저장은 부르는 쪽이 한다.
 * 전송 대상 시각은 확정 시각이다 (commit 되면 바로 relay 대상).
 */
@RequiredArgsConstructor
@Component
public class OrderConfirmedOutboxFactory {
    private final OrderConfirmedPayloadSerializer payloadSerializer;

    public OrderEventOutboxModel create(OrderModel confirmedOrder) {
        OrderConfirmedEvent event = OrderConfirmedEvent.from(confirmedOrder);
        return OrderEventOutboxModel.pending(
            event.eventId(),
            event.orderId(),
            OrderConfirmedEvent.EVENT_TYPE,
            OrderConfirmedEvent.SCHEMA_VERSION,
            event.messageKey(),
            payloadSerializer.serialize(event),
            confirmedOrder.getConfirmedAt()
        );
    }
}

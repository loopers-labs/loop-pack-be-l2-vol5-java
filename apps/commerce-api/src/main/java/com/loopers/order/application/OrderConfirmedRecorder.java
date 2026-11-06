package com.loopers.order.application;

import com.loopers.order.domain.OrderConfirmed;
import com.loopers.order.domain.OrderModel;
import com.loopers.outbox.application.port.in.OutboxAppendUseCase;
import com.loopers.outbox.application.port.in.OutboxAppendUseCase.OrderOutboxEntry;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 주문이 처음 CONFIRMED가 된 그 트랜잭션 안에서 OrderConfirmed 발행 기록을 남긴다 (W6 D-2·D-3, ADR-W6-01).
 * eventId는 여기서 한 번 만든 UUID다 — 기록에 저장되므로 재발행해도 같은 값이다. 도메인은 난수를 모른다.
 * W7: 행만 남긴다. topic은 relay가 설정에서 읽고, 보내는 것도 relay다 (docs/week7/plan.md D-0).
 */
@Component
public class OrderConfirmedRecorder {

    private final OutboxAppendUseCase outboxAppendUseCase;

    public OrderConfirmedRecorder(OutboxAppendUseCase outboxAppendUseCase) {
        this.outboxAppendUseCase = outboxAppendUseCase;
    }

    public void record(OrderModel confirmedOrder) {
        OrderConfirmed event = OrderConfirmed.from(UUID.randomUUID().toString(), confirmedOrder);
        outboxAppendUseCase.append(new OrderOutboxEntry(
            event.eventId(),
            event.orderId(),
            event.eventType(),
            event.schemaVersion(),
            event
        ));
    }
}

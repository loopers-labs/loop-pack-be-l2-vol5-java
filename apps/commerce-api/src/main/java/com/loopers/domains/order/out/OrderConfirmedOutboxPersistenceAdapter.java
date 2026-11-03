package com.loopers.domains.order.out;

import com.loopers.domains.order.app.port.OrderConfirmedOutbox;
import com.loopers.domains.order.app.port.OrderConfirmedPayload;
import java.time.Clock;
import org.springframework.stereotype.Repository;

// 확정 트랜잭션에 참여해 insert한다(별도 트랜잭션·commit 뒤 기록이면 확정과 기록이 따로 남을 수 있다).
// 바로 flush해 eventId 유일 제약 위반·DB 오류가 commit 때가 아니라 이 호출에서 드러나게 하고, 예외는 잡지 않고 올린다.
@Repository
public class OrderConfirmedOutboxPersistenceAdapter implements OrderConfirmedOutbox {
    private final OrderConfirmedOutboxJpaRepository orderConfirmedOutboxJpaRepository;
    private final Clock clock;

    public OrderConfirmedOutboxPersistenceAdapter(OrderConfirmedOutboxJpaRepository orderConfirmedOutboxJpaRepository,
        Clock clock) {
        this.orderConfirmedOutboxJpaRepository = orderConfirmedOutboxJpaRepository;
        this.clock = clock;
    }

    @Override
    public void append(OrderConfirmedPayload payload) {
        String message = OrderConfirmedMessageSerializer.serialize(payload);
        orderConfirmedOutboxJpaRepository.saveAndFlush(OrderConfirmedOutboxJpaEntity.pending(payload.eventId(),
            payload.orderId(), OrderConfirmedPayload.EVENT_TYPE, OrderConfirmedPayload.SCHEMA_VERSION, payload.key(), message,
            clock.instant()));
    }
}

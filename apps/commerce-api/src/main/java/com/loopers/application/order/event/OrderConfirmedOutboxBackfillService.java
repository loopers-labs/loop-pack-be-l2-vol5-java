package com.loopers.application.order.event;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class OrderConfirmedOutboxBackfillService {

    private final OrderRepository orderRepository;
    private final OrderConfirmedOutboxWriter outboxWriter;

    @Transactional
    public BackfillResult backfill(Long orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new IllegalStateException("복구할 주문을 찾을 수 없습니다. orderId=" + orderId));
        OrderConfirmedEvent event = OrderConfirmedEvent.from(order);
        boolean inserted = outboxWriter.ensureRecorded(event);
        return new BackfillResult(orderId, event.getEventId(), inserted);
    }

    public record BackfillResult(Long orderId, String eventId, boolean inserted) {}
}

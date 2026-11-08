package com.loopers.domain.order.repository;

import com.loopers.domain.order.entity.OrderConfirmedOutbox;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** DB 의 order_id 유일 제약을 흉내 내 같은 주문의 두 번째 기록은 실패시킴 */
public class FakeOrderConfirmedOutboxRepository implements OrderConfirmedOutboxRepository {

    private final Map<Long, OrderConfirmedOutbox> byOrderId = new LinkedHashMap<>();
    private long sequence = 0L;

    @Override
    public OrderConfirmedOutbox save(OrderConfirmedOutbox outbox) {
        if (outbox.getId() == null || outbox.getId() == 0L) {
            if (byOrderId.containsKey(outbox.getOrderId())) {
                throw new IllegalStateException("이미 기록된 주문: " + outbox.getOrderId());
            }
            ReflectionTestUtils.setField(outbox, "id", ++sequence);
        }
        byOrderId.put(outbox.getOrderId(), outbox);
        return outbox;
    }

    @Override
    public Optional<OrderConfirmedOutbox> findById(Long id) {
        return byOrderId.values().stream().filter(outbox -> outbox.getId().equals(id)).findFirst();
    }

    @Override
    public Optional<OrderConfirmedOutbox> findByEventId(String eventId) {
        return byOrderId.values().stream().filter(outbox -> outbox.getEventId().equals(eventId)).findFirst();
    }

    @Override
    public List<OrderConfirmedOutbox> findDue(ZonedDateTime now, int limit) {
        return byOrderId.values().stream()
            .filter(outbox -> outbox.isDue(now))
            .sorted(Comparator.comparing(OrderConfirmedOutbox::getId))
            .limit(limit)
            .toList();
    }

    @Override
    public Optional<OrderConfirmedOutbox> findByOrderId(Long orderId) {
        return Optional.ofNullable(byOrderId.get(orderId));
    }

    public List<OrderConfirmedOutbox> findAll() {
        return new ArrayList<>(byOrderId.values());
    }
}

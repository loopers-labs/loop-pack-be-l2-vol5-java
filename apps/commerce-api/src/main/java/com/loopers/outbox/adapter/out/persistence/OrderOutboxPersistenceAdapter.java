package com.loopers.outbox.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.outbox.application.port.out.OrderOutboxPort;
import com.loopers.outbox.domain.OrderOutbox;
import com.loopers.outbox.domain.OrderOutboxStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class OrderOutboxPersistenceAdapter implements OrderOutboxPort {

    /** PENDING은 재시도 시각, SENDING은 lease 기한이 next_attempt_at이다 — 한 조건으로 둘 다 잡는다 (D-0 "SENDING을 둔다") */
    private static final EnumSet<OrderOutboxStatus> DUE_STATUSES = EnumSet.of(OrderOutboxStatus.PENDING, OrderOutboxStatus.SENDING);

    private final OrderOutboxJpaRepository orderOutboxJpaRepository;
    private final ObjectMapper objectMapper;

    @Override
    public OrderOutbox append(String eventId, Long orderId, String eventType, int schemaVersion, Object payload, ZonedDateTime now) {
        return orderOutboxJpaRepository.save(OrderOutbox.pending(eventId, orderId, eventType, schemaVersion, toJson(payload), now));
    }

    @Override
    public OrderOutbox save(OrderOutbox outbox) {
        return orderOutboxJpaRepository.save(outbox);
    }

    @Override
    public Optional<OrderOutbox> findByEventId(String eventId) {
        return orderOutboxJpaRepository.findByEventId(eventId);
    }

    @Override
    public List<OrderOutbox> findDue(ZonedDateTime now, int limit) {
        return orderOutboxJpaRepository.findDue(DUE_STATUSES, now, PageRequest.of(0, limit));
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("이벤트 payload를 JSON으로 바꾸지 못했습니다: " + payload.getClass().getName(), e);
        }
    }
}

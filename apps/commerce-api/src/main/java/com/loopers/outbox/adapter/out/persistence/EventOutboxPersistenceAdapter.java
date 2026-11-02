package com.loopers.outbox.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.outbox.application.port.out.EventOutboxPort;
import com.loopers.outbox.domain.EventOutbox;
import com.loopers.outbox.domain.OutboxMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class EventOutboxPersistenceAdapter implements EventOutboxPort {

    private final EventOutboxJpaRepository eventOutboxJpaRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String append(OutboxMessage message) {
        String payload = toJson(message.payload());
        eventOutboxJpaRepository.save(EventOutbox.of(message, payload));
        return payload;
    }

    @Override
    public void markPublished(String eventId, int partition, long offset, ZonedDateTime publishedAt) {
        eventOutboxJpaRepository.markPublished(eventId, partition, offset, publishedAt);
    }

    @Override
    public void markFailed(String eventId, String error) {
        eventOutboxJpaRepository.markFailed(eventId, error, ZonedDateTime.now());
    }

    @Override
    public Optional<EventOutbox> findByEventId(String eventId) {
        return eventOutboxJpaRepository.findByEventId(eventId);
    }

    @Override
    public List<EventOutbox> findUnpublishedCreatedBefore(ZonedDateTime createdBefore) {
        return eventOutboxJpaRepository.findUnpublishedCreatedBefore(createdBefore);
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("이벤트 payload를 JSON으로 바꾸지 못했습니다: " + payload.getClass().getName(), e);
        }
    }
}

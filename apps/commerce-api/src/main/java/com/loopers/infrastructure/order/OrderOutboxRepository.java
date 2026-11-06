package com.loopers.infrastructure.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderOutbox;
import com.loopers.application.order.OrderOutboxMessage;
import com.loopers.application.order.OutboxDeliveryStore;
import com.loopers.application.order.OutboxRetryPolicy;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class OrderOutboxRepository implements OrderOutbox, OutboxDeliveryStore {
    private final OrderOutboxJpaRepository repository;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    public OrderOutboxRepository(OrderOutboxJpaRepository repository, ObjectMapper objectMapper,
            EntityManager entityManager) {
        this.repository = repository;
        this.objectMapper = objectMapper.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(OrderOutboxMessage event) {
        validatePayload(event.payload());
        persist(event);
    }

    private void persist(OrderOutboxMessage event) {
        entityManager.persist(new OrderOutboxEntity(event, Instant.now()));
        entityManager.flush();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean recordIfAbsent(OrderOutboxMessage event) {
        JsonNode expectedPayload = validatePayload(event.payload());
        var existing = repository.findById(event.eventId());
        if (existing.isEmpty()) {
            persist(event);
            return true;
        }
        try {
            var stored = existing.get();
            var storedPayload = objectMapper.readTree(stored.payload());
            if (!stored.matchesMetadata(event)
                    || storedPayload == null || !storedPayload.equals(expectedPayload)) {
                throw new IllegalStateException("Existing outbox event does not match requested event");
            }
            return false;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Existing outbox payload cannot be read", exception);
        }
    }

    private JsonNode validatePayload(String payload) {
        try {
            JsonNode parsed = objectMapper.readTree(payload);
            if (parsed == null) {
                throw new IllegalArgumentException("Outbox payload must be valid JSON");
            }
            return parsed;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Outbox payload must be valid JSON", exception);
        }
    }

    @Override
    @Transactional
    public int blockExpiredAttempts(Instant now) {
        return repository.blockExpiredAttempts(now, OutboxRetryPolicy.MAX_ATTEMPTS);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> readyEventIds(Instant now, int limit) {
        return repository.readyEventIds(now, OutboxRetryPolicy.MAX_ATTEMPTS, PageRequest.of(0, limit));
    }

    @Override
    @Transactional
    public Optional<DeliveryEvent> beginAttempt(String eventId, Instant now, Instant retryAt) {
        return repository.findById(eventId).flatMap(event -> event.beginAttempt(now, retryAt));
    }

    @Override
    @Transactional
    public void markPublished(String eventId, Instant now) {
        repository.findById(eventId).orElseThrow().markPublished(now);
    }

    @Override
    @Transactional
    public void recordFailure(String eventId, Instant nextAttemptAt, String error) {
        repository.findById(eventId).orElseThrow().recordFailure(nextAttemptAt, error);
    }

    @Override
    @Transactional
    public boolean resume(String eventId, Instant now) {
        return repository.resumeBlocked(eventId, now) == 1;
    }

    @Transactional
    public int deletePublishedBefore(Instant before) {
        return repository.deletePublishedBefore(before);
    }
}

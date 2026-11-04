package com.loopers.infrastructure.persistence.ordering;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.loopers.domain.ordering.model.OrderConfirmation;
import com.loopers.infrastructure.persistence.ordering.entity.OrderConfirmationInboxJpaEntity;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

final class OrderConfirmationInboxMapper {
    private static final JsonMapper MAPPER = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .build();

    private OrderConfirmationInboxMapper() {}

    static OrderConfirmationInboxJpaEntity toEntity(OrderConfirmation confirmation) {
        String eventId = confirmation.eventId().toString();
        var snapshot = new Snapshot(OrderConfirmation.EVENT_TYPE, OrderConfirmation.SCHEMA_VERSION, eventId,
            confirmation.orderId(), confirmation.confirmedAt().toString(), confirmation.items());
        try {
            return new OrderConfirmationInboxJpaEntity(eventId, confirmation.orderId(), snapshot.eventType(),
                snapshot.schemaVersion(), confirmation.confirmedAt(), MAPPER.writeValueAsString(snapshot),
                Instant.now().truncatedTo(ChronoUnit.MICROS));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Inbox 확정 사실을 저장 형식으로 변환할 수 없습니다.", exception);
        }
    }

    static OrderConfirmation toDomain(OrderConfirmationInboxJpaEntity entity) {
        try {
            var snapshot = MAPPER.readValue(entity.getNormalizedPayload(), Snapshot.class);
            var confirmation = new OrderConfirmation(UUID.fromString(snapshot.eventId()), snapshot.orderId(),
                Instant.parse(snapshot.confirmedAt()), snapshot.items());
            if (!OrderConfirmation.EVENT_TYPE.equals(snapshot.eventType())
                || snapshot.schemaVersion() != OrderConfirmation.SCHEMA_VERSION
                || !entity.getEventType().equals(snapshot.eventType()) || entity.getSchemaVersion() != snapshot.schemaVersion()
                || !entity.getEventId().equals(snapshot.eventId()) || !confirmation.eventId().toString().equals(snapshot.eventId())
                || entity.getOrderId() != confirmation.orderId()
                || !entity.getConfirmedAt().equals(confirmation.confirmedAt())) {
                throw new IllegalStateException("Inbox 식별 컬럼과 저장된 확정 사실이 일치하지 않습니다.");
            }
            return confirmation;
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new IllegalStateException("Inbox 확정 사실을 복원할 수 없습니다. inboxId=" + entity.getId(), exception);
        }
    }

    private record Snapshot(String eventType, int schemaVersion, String eventId, long orderId, String confirmedAt,
                            List<OrderConfirmation.Item> items) {}
}

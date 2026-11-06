package com.loopers.infrastructure.outbox;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.loopers.domain.outbox.OutboxMessage;
import com.loopers.domain.outbox.OutboxMessageMatcher;

import java.util.Objects;

/** Property order and whitespace are irrelevant; canonical array order and all saved facts must agree. */
public class JsonOutboxMessageMatcher implements OutboxMessageMatcher {
    private final ObjectReader reader;

    public JsonOutboxMessageMatcher(ObjectMapper mapper) {
        this.reader = Objects.requireNonNull(mapper, "mapper").reader()
            .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override
    public boolean matches(OutboxMessage expected, OutboxMessage actual) {
        if (expected == null || actual == null || !sameMetadata(expected, actual)) {
            return false;
        }
        try {
            JsonNode expectedPayload = reader.readTree(expected.getPayload());
            JsonNode actualPayload = reader.readTree(actual.getPayload());
            return expectedPayload != null && expectedPayload.isObject()
                && actualPayload != null && actualPayload.isObject() && expectedPayload.equals(actualPayload);
        } catch (JsonProcessingException exception) {
            // Reject malformed facts without logging payload-bearing parser diagnostics or rewriting the row.
            return false;
        }
    }

    private boolean sameMetadata(OutboxMessage expected, OutboxMessage actual) {
        return Objects.equals(expected.getEventId(), actual.getEventId())
            && Objects.equals(expected.getAggregateType(), actual.getAggregateType())
            && Objects.equals(expected.getAggregateId(), actual.getAggregateId())
            && Objects.equals(expected.getEventType(), actual.getEventType())
            && expected.getSchemaVersion() == actual.getSchemaVersion()
            && Objects.equals(expected.getTopic(), actual.getTopic())
            && Objects.equals(expected.getMessageKey(), actual.getMessageKey());
    }
}

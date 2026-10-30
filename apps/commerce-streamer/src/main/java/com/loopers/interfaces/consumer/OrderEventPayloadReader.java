package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.activity.PurchaseReading;
import com.loopers.domain.activity.UnreadableReason;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
class OrderEventPayloadReader {

    private static final String EVENT_TYPE = "eventType";
    private static final String SCHEMA_VERSION = "schemaVersion";
    private static final String CONFIRMED = "OrderConfirmed";
    private static final int SUPPORTED_VERSION = 1;
    private static final OrderEventReading MALFORMED =
        new OrderEventReading.Confirmed(new PurchaseReading.Unreadable(UnreadableReason.MALFORMED));

    private final ObjectMapper objectMapper;

    OrderEventPayloadReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    OrderEventReading read(String key, byte[] value) {
        try {
            JsonNode root = parse(value);
            String eventType = requireText(root, EVENT_TYPE);
            if (!CONFIRMED.equals(eventType)) {
                return new OrderEventReading.NotHandled(eventType);
            }
            requireSupportedVersion(root);
            return new OrderEventReading.Confirmed(bind(root).toReading(key));
        } catch (MalformedRecord e) {
            return MALFORMED;
        }
    }

    private JsonNode parse(byte[] value) {
        try {
            JsonNode root = objectMapper.readTree(value);
            if (root == null || !root.isObject()) {
                throw new MalformedRecord();
            }
            return root;
        } catch (IOException e) {
            throw new MalformedRecord();
        }
    }

    private static String requireText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual()) {
            throw new MalformedRecord();
        }
        return node.asText();
    }

    private static void requireSupportedVersion(JsonNode root) {
        JsonNode node = root.get(SCHEMA_VERSION);
        if (node == null || !node.isInt()) {
            throw new MalformedRecord();
        }
        if (node.intValue() != SUPPORTED_VERSION) {
            throw new UnsupportedSchemaVersionException(node.intValue());
        }
    }

    private OrderConfirmedPayload bind(JsonNode root) {
        try {
            OrderConfirmedPayload payload = objectMapper.treeToValue(root, OrderConfirmedPayload.class);
            if (payload.hasEmptyItem()) {
                throw new MalformedRecord();
            }
            return payload;
        } catch (IOException | IllegalArgumentException e) {
            throw new MalformedRecord();
        }
    }

    private static final class MalformedRecord extends RuntimeException {

        private MalformedRecord() {
            super(null, null, false, false);
        }
    }
}

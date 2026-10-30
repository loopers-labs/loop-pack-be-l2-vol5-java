package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.activity.ConfirmedPurchase;
import com.loopers.domain.activity.PurchaseReading;
import com.loopers.domain.activity.UnreadableReason;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
class OrderEventPayloadReader {

    private static final String CONFIRMED = "OrderConfirmed";
    private static final int SUPPORTED_VERSION = 1;

    private final ObjectMapper objectMapper;

    OrderEventPayloadReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    OrderEventReading read(String key, byte[] value) {
        JsonNode root;
        try {
            root = objectMapper.readTree(value);
        } catch (IOException e) {
            return malformed();
        }
        if (root == null || !root.isObject()) {
            return malformed();
        }

        JsonNode eventType = root.get("eventType");
        if (eventType == null || !eventType.isTextual()) {
            return malformed();
        }
        if (!CONFIRMED.equals(eventType.asText())) {
            return new OrderEventReading.NotHandled(eventType.asText());
        }

        JsonNode schemaVersion = root.get("schemaVersion");
        if (schemaVersion == null || !schemaVersion.isInt()) {
            return malformed();
        }
        if (schemaVersion.intValue() != SUPPORTED_VERSION) {
            throw new UnsupportedSchemaVersionException(schemaVersion.intValue());
        }

        OrderConfirmedPayload payload;
        try {
            payload = objectMapper.treeToValue(root, OrderConfirmedPayload.class);
        } catch (IOException | IllegalArgumentException e) {
            return malformed();
        }
        if (payload.hasEmptyItem()) {
            return malformed();
        }
        return new OrderEventReading.Confirmed(ConfirmedPurchase.read(
            key, payload.eventId(), payload.orderId(), payload.confirmedAt(), payload.lines()));
    }

    private static OrderEventReading malformed() {
        return new OrderEventReading.Confirmed(new PurchaseReading.Unreadable(UnreadableReason.MALFORMED));
    }
}

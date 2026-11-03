package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.loopers.domain.purchase.OrderConfirmation;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Validates the wire shape before constructing a fact; coercion and ambiguous JSON are not accepted. */
@Component
public class OrderConfirmedEventParser {
    private final ObjectReader reader;

    public OrderConfirmedEventParser(ObjectMapper mapper) {
        reader = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public OrderConfirmation parseAndValidate(String key, byte[] value) {
        try {
            JsonNode root = reader.readTree(value);
            if (root == null || !root.isObject()) {
                throw invalid();
            }
            long orderId = longValue(root.get("orderId"));
            if (!Long.toString(orderId).equals(key)) {
                throw invalid();
            }
            JsonNode itemNodes = root.get("items");
            if (itemNodes == null || !itemNodes.isArray()) {
                throw invalid();
            }
            List<OrderConfirmation.Item> items = new ArrayList<>();
            for (JsonNode item : itemNodes) {
                if (!item.isObject()) {
                    throw invalid();
                }
                items.add(new OrderConfirmation.Item(longValue(item.get("productId")), intValue(item.get("quantity"))));
            }
            return new OrderConfirmation(text(root.get("eventType")), intValue(root.get("schemaVersion")),
                text(root.get("eventId")), orderId, Instant.parse(text(root.get("confirmedAt"))), items);
        } catch (IOException | IllegalArgumentException | DateTimeException exception) {
            // Parser exceptions can include raw JSON snippets. Do not retain them for a listener to log later.
            throw invalid();
        }
    }

    private String text(JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw invalid();
        }
        return node.textValue();
    }

    private long longValue(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw invalid();
        }
        return node.longValue();
    }

    private int intValue(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw invalid();
        }
        return node.intValue();
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid order confirmation event");
    }
}

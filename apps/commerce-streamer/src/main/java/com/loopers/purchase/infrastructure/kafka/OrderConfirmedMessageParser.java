package com.loopers.purchase.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.purchase.application.event.OrderConfirmedEvent;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class OrderConfirmedMessageParser {

    private final ObjectMapper objectMapper;

    public OrderConfirmedMessageParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public OrderConfirmedEvent parse(byte[] payload) {
        try {
            JsonNode json = objectMapper.readTree(payload);
            if (json == null) {
                throw new IllegalArgumentException("OrderConfirmed Kafka payload must not be null");
            }
            validateBusinessDtoCompatibility(json);
            return toApplicationEvent(json);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid OrderConfirmed JSON payload", exception);
        }
    }

    private void validateBusinessDtoCompatibility(JsonNode payload) {
        if (!payload.path("eventId").isTextual()
            || !payload.path("orderId").isIntegralNumber()
            || !payload.path("orderId").canConvertToLong()
            || !payload.path("confirmedAt").isTextual()
            || !payload.path("items").isArray()) {
            throw new IllegalArgumentException("OrderConfirmed payload is incompatible with purchase activity DTO");
        }

        try {
            OffsetDateTime.parse(payload.path("confirmedAt").asText());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                "OrderConfirmed confirmedAt is incompatible with purchase activity DTO",
                exception
            );
        }

        for (JsonNode item : payload.path("items")) {
            if (!item.path("productId").isIntegralNumber()
                || !item.path("productId").canConvertToLong()
                || !item.path("quantity").isIntegralNumber()
                || !item.path("quantity").canConvertToInt()) {
                throw new IllegalArgumentException("OrderConfirmed item is incompatible with purchase activity DTO");
            }
        }
    }

    private OrderConfirmedEvent toApplicationEvent(JsonNode message) {
        return new OrderConfirmedEvent(
            message.path("eventId").asText(),
            message.path("orderId").longValue(),
            OffsetDateTime.parse(message.path("confirmedAt").asText()).toInstant(),
            toApplicationItems(message.path("items"))
        );
    }

    private List<OrderConfirmedEvent.Item> toApplicationItems(JsonNode items) {
        List<OrderConfirmedEvent.Item> result = new ArrayList<>();
        for (JsonNode item : items) {
            result.add(new OrderConfirmedEvent.Item(
                item.path("productId").longValue(),
                Math.toIntExact(item.path("quantity").longValue())
            ));
        }
        return List.copyOf(result);
    }
}

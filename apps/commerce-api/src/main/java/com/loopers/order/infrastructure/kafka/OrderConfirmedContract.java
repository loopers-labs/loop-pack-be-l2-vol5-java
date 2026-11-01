package com.loopers.order.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

@Component
public class OrderConfirmedContract {

    private static final String RESOURCE = "events/order/order-confirmed-v1.contract.json";

    private final String topic;

    public OrderConfirmedContract(ObjectMapper objectMapper) {
        try (InputStream contract = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (contract == null) {
                throw new IllegalStateException("Missing OrderConfirmed contract resource: " + RESOURCE);
            }
            JsonNode json = objectMapper.readTree(contract);
            this.topic = json.path("topic").asText();
            if (topic.isBlank()) {
                throw new IllegalStateException("OrderConfirmed contract topic must not be blank");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read OrderConfirmed contract: " + RESOURCE, exception);
        }
    }

    public String topic() {
        return topic;
    }
}

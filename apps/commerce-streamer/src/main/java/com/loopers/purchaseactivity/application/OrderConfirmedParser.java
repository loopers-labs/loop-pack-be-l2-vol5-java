package com.loopers.purchaseactivity.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.loopers.kafka.event.OrderConfirmed;

import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OrderConfirmedParser {
    private final ObjectMapper objectMapper;

    public OrderConfirmedParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.objectMapper.coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
    }

    public OrderConfirmed parseAndValidate(String key, byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("OrderConfirmed must not be a tombstone");
        }
        try {
            OrderConfirmed event = objectMapper.readValue(value, OrderConfirmed.class);
            if (event == null) {
                throw new IllegalArgumentException("OrderConfirmed must not be null");
            }
            event.validateKey(key);
            return event;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid OrderConfirmed payload", failure);
        }
    }
}

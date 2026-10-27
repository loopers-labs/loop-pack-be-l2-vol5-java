package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.activity.PurchaseActivityCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * record key·value(byte[] JSON) → PurchaseActivityCommand. 메시지 계약(eventType "OrderConfirmed", schemaVersion 1, key = orderId 문자열)을 확인한다.
 * 품목·eventId 규칙은 도메인(OrderConfirmation)이 확인한다.
 */
@RequiredArgsConstructor
@Component
public class OrderConfirmedMessageParser {
    private final ObjectMapper objectMapper;

    public PurchaseActivityCommand parse(String key, byte[] value) {
        if (value == null) {
            throw new InvalidOrderConfirmedMessageException("value 가 없습니다. [key = " + key + "]");
        }
        OrderConfirmedMessage message;
        try {
            message = objectMapper.readValue(value, OrderConfirmedMessage.class);
        } catch (IOException e) {
            throw new InvalidOrderConfirmedMessageException("JSON 을 읽을 수 없습니다. [key = " + key + "]", e);
        }
        if (!OrderConfirmedMessage.EVENT_TYPE.equals(message.eventType())) {
            throw new InvalidOrderConfirmedMessageException("eventType 이 다릅니다. [key = " + key + ", eventType = " + message.eventType() + "]");
        }
        if (message.schemaVersion() == null || message.schemaVersion() != OrderConfirmedMessage.SCHEMA_VERSION) {
            throw new InvalidOrderConfirmedMessageException("지원하지 않는 schemaVersion 입니다. [key = " + key + ", schemaVersion = " + message.schemaVersion() + "]");
        }
        if (message.orderId() == null || !String.valueOf(message.orderId()).equals(key)) {
            throw new InvalidOrderConfirmedMessageException("record key 와 orderId 가 다릅니다. [key = " + key + ", orderId = " + message.orderId() + "]");
        }
        return new PurchaseActivityCommand(
            message.eventId(),
            message.orderId(),
            parseInstant(key, message.confirmedAt()),
            message.items() == null ? null : message.items().stream()
                .map(item -> item == null ? null : new PurchaseActivityCommand.Item(item.productId(), item.quantity()))
                .toList()
        );
    }

    private static Instant parseInstant(String key, String confirmedAt) {
        if (confirmedAt == null) {
            throw new InvalidOrderConfirmedMessageException("confirmedAt 이 없습니다. [key = " + key + "]");
        }
        try {
            return Instant.parse(confirmedAt);
        } catch (DateTimeParseException e) {
            throw new InvalidOrderConfirmedMessageException("confirmedAt 형식이 아닙니다. [key = " + key + ", confirmedAt = " + confirmedAt + "]", e);
        }
    }
}

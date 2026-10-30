package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.purchase.dto.SavePurchaseActivityCommand;
import com.loopers.domain.purchase.PurchaseLine;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code order-events} 의 OrderConfirmed(schemaVersion 1) record 를 검증해 구매 활동 기록 입력으로 바꾼다. 계약은 발행하는
 * 앱과 코드를 공유하지 않고 이쪽에서 따로 확인한다. 하나라도 어긋나면 {@link InvalidOrderEventException} 이다.
 */
@RequiredArgsConstructor
@Component
public class OrderConfirmedMessageParser {

    private static final String EVENT_TYPE = "OrderConfirmed";
    private static final int SCHEMA_VERSION = 1;
    private static final String EVENT_ID_PREFIX = "order-confirmed:v1:";

    private final ObjectMapper objectMapper;

    public SavePurchaseActivityCommand parse(ConsumerRecord<String, byte[]> record) {
        try {
            return parse(record.key(), record.value());
        } catch (InvalidOrderEventException e) {
            throw new InvalidOrderEventException(position(record) + e.getMessage(), e);
        } catch (IOException | RuntimeException e) {
            throw new InvalidOrderEventException(position(record) + "OrderConfirmed record 를 해석하지 못했습니다: " + e.getMessage(), e);
        }
    }

    // 컨테이너가 멈췄을 때 어느 record 가 원인인지 바로 알 수 있게 위치를 메시지에 남긴다.
    private String position(ConsumerRecord<String, byte[]> record) {
        return "[" + record.topic() + "-" + record.partition() + "@" + record.offset() + "] ";
    }

    private SavePurchaseActivityCommand parse(String key, byte[] value) throws IOException {
        require(value != null, "record 의 값이 없습니다.");
        JsonNode root = objectMapper.readTree(value);
        require(EVENT_TYPE.equals(text(root, "eventType")), "eventType 이 OrderConfirmed 가 아닙니다.");
        require(integer(root, "schemaVersion") == SCHEMA_VERSION, "지원하지 않는 schemaVersion 입니다.");
        long orderId = integer(root, "orderId");
        require(String.valueOf(orderId).equals(key), "record key 와 orderId 가 다릅니다.");
        String eventId = text(root, "eventId");
        require((EVENT_ID_PREFIX + orderId).equals(eventId), "eventId 가 orderId 에서 유도한 값과 다릅니다.");
        ZonedDateTime confirmedAt = ZonedDateTime.ofInstant(Instant.parse(text(root, "confirmedAt")), ZoneOffset.UTC);
        return new SavePurchaseActivityCommand(eventId, orderId, confirmedAt, lines(root));
    }

    private List<PurchaseLine> lines(JsonNode root) {
        JsonNode items = root.get("items");
        require(items != null && items.isArray() && !items.isEmpty(), "items 가 비어 있습니다.");
        List<PurchaseLine> lines = new ArrayList<>();
        for (JsonNode item : items) {
            long productId = integer(item, "productId");
            long quantity = integer(item, "quantity");
            require(quantity >= 1 && quantity <= Integer.MAX_VALUE, "수량은 1 이상이어야 합니다.");
            lines.add(new PurchaseLine(productId, (int) quantity));
        }
        return lines;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        require(value != null && value.isTextual(), field + " 가 없거나 문자열이 아닙니다.");
        return value.asText();
    }

    private long integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        require(value != null && value.isIntegralNumber() && value.canConvertToLong(), field + " 가 없거나 정수가 아닙니다.");
        return value.asLong();
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new InvalidOrderEventException(message);
        }
    }
}

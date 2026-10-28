package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.loopers.domain.purchase.OrderConfirmation;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.loopers.interfaces.consumer.OrderConfirmedJsonFields.integer;
import static com.loopers.interfaces.consumer.OrderConfirmedJsonFields.text;

@Component
public class OrderConfirmedParser {
    private final ObjectReader reader;
    private final Map<Integer, OrderConfirmedVersionReader> versionReaders;

    public OrderConfirmedParser(ObjectMapper objectMapper, List<OrderConfirmedVersionReader> versionReaders) {
        reader = objectMapper.reader()
            .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        Map<Integer, OrderConfirmedVersionReader> registered = new HashMap<>();
        for (OrderConfirmedVersionReader versionReader : versionReaders) {
            int version = versionReader.schemaVersion();
            if (version <= 0) {
                throw new IllegalArgumentException("확정 이벤트 버전은 양수여야 합니다.");
            }
            if (registered.putIfAbsent(version, versionReader) != null) {
                throw new IllegalArgumentException("확정 이벤트 버전 해석기가 중복 등록됐습니다: " + version);
            }
        }
        this.versionReaders = Map.copyOf(registered);
    }

    public OrderConfirmation parseAndValidate(String key, byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("확정 이벤트 payload가 없습니다.");
        }
        try {
            JsonNode json = reader.readTree(value);
            if (json == null || !json.isObject() || !"OrderConfirmed".equals(text(json, "eventType"))) {
                throw new IllegalArgumentException("지원하지 않는 확정 이벤트 형식입니다.");
            }
            long version = integer(json, "schemaVersion");
            OrderConfirmedVersionReader versionReader = version > 0 && version <= Integer.MAX_VALUE
                ? versionReaders.get((int) version) : null;
            if (versionReader == null) {
                throw new IllegalArgumentException("지원하지 않는 확정 이벤트 버전입니다: " + version);
            }
            OrderConfirmation confirmation = versionReader.read(json);
            if (!Long.toString(confirmation.orderId()).equals(key)) {
                throw new IllegalArgumentException("Kafka key와 확정 주문 ID가 일치하지 않습니다.");
            }
            return confirmation;
        } catch (IOException | DateTimeParseException e) {
            throw new IllegalArgumentException("확정 이벤트 JSON 또는 확정 시각이 유효하지 않습니다.", e);
        }
    }
}

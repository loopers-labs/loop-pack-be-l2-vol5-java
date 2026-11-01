package com.loopers.interfaces.consumer.order;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.purchaseactivity.OrderConfirmation;
import com.loopers.domain.purchaseactivity.PurchaseActivityErrorCode;
import com.loopers.domain.purchaseactivity.PurchaseActivityException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OrderConfirmed v1 의 key·value 를 검증해 불변 사실로 바꾼다(소비 설계 4절).
 *
 * <p>공통 ObjectMapper 는 단일 값을 배열로 받는 등 관대하므로 쓰지 않고, 기본값의 전용 mapper 로 트리만 읽어 타입을 직접 본다.
 * byte[] 를 Jackson 에 바로 주면 UTF-16/32 를 자동 감지하므로 먼저 UTF-8 로 엄격하게 디코딩한다.
 * 오류 메시지에는 필드 이름만 쓰고 원문 payload·key·Jackson 오류를 담지 않는다.
 */
@Component
public class OrderConfirmedParser {

    private static final Pattern CANONICAL_ID = Pattern.compile("[1-9][0-9]*");
    /** UTC Z·초 필수·소수초 최대 6자리. 연도 4자리 1000~9999 는 DB DATETIME(6) 범위와 같다. */
    private static final Pattern CONFIRMED_AT = Pattern.compile(
        "([1-9][0-9]{3})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.([0-9]{1,6}))?Z");

    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build());

    public OrderConfirmation parseAndValidate(String key, byte[] value) {
        JsonNode root = readSingleObject(decodeUtf8(value));

        String eventType = requiredText(root, "eventType");
        long schemaVersion = requiredLong(root, "schemaVersion");
        String eventId = requiredText(root, "eventId");
        long orderId = requiredPositiveLong(root, "orderId");
        String confirmedAtText = requiredText(root, "confirmedAt");
        JsonNode itemsNode = required(root, "items");

        if (!OrderConfirmation.EVENT_TYPE.equals(eventType) || schemaVersion != OrderConfirmation.SCHEMA_VERSION) {
            throw invalid("지원하지 않는 eventType·schemaVersion 입니다.");
        }
        if (key == null || !CANONICAL_ID.matcher(key).matches()) {
            throw invalid("key 는 선행 0·공백 없는 양수 10진 문자열이어야 합니다.");
        }
        if (!key.equals(Long.toString(orderId))) {
            throw new PurchaseActivityException(PurchaseActivityErrorCode.IDENTITY_CONFLICT, "key 와 orderId 가 다릅니다.");
        }
        if (eventId.isBlank() || eventId.length() > OrderConfirmation.MAX_EVENT_ID_LENGTH
            || !eventId.chars().allMatch(c -> c <= 0x7F)) {
            throw invalid("eventId 는 64자 이하의 비어 있지 않은 ASCII 문자열이어야 합니다.");
        }
        if (!eventId.equals(OrderConfirmation.eventIdOf(orderId))) {
            throw new PurchaseActivityException(PurchaseActivityErrorCode.IDENTITY_CONFLICT,
                "eventId 가 orderId 에서 유도한 값과 다릅니다.");
        }
        ZonedDateTime confirmedAt = parseConfirmedAt(confirmedAtText);
        List<OrderConfirmation.Item> originalItems = originalItems(itemsNode);

        return OrderConfirmation.normalize(eventType, OrderConfirmation.SCHEMA_VERSION, eventId, orderId, confirmedAt,
            originalItems);
    }

    private static String decodeUtf8(byte[] value) {
        if (value == null || value.length == 0) {
            throw invalid("value 가 비어 있습니다.");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value))
                .toString();
        } catch (CharacterCodingException malformed) {
            throw invalid("value 가 올바른 UTF-8 이 아닙니다.");
        }
    }

    /** 객체 하나만 허용한다. 중복 property 는 parser 가, 뒤따르는 token·추가 문서는 여기서 거절한다. */
    private JsonNode readSingleObject(String text) {
        try (JsonParser parser = mapper.createParser(text)) {
            JsonNode root = mapper.readTree(parser);
            if (root == null || !root.isObject()) {
                throw invalid("value 는 JSON object 여야 합니다.");
            }
            if (parser.nextToken() != null) {
                throw invalid("JSON object 뒤에 추가 내용이 있습니다.");
            }
            return root;
        } catch (IOException malformed) {
            throw invalid("value 가 올바른 JSON 이 아닙니다.");
        }
    }

    private static List<OrderConfirmation.Item> originalItems(JsonNode itemsNode) {
        if (!itemsNode.isArray() || itemsNode.isEmpty()) {
            throw invalid("items 는 비어 있지 않은 배열이어야 합니다.");
        }
        List<OrderConfirmation.Item> items = new ArrayList<>(itemsNode.size());
        for (JsonNode element : itemsNode) {
            if (!element.isObject()) {
                throw invalid("items 의 원소는 object 여야 합니다.");
            }
            // 원본 항목마다 양수를 확인한다. 합산 뒤에만 보면 -1 과 3 이 2 로 가려진다.
            items.add(new OrderConfirmation.Item(
                requiredPositiveLong(element, "productId"), requiredPositiveLong(element, "quantity")));
        }
        return items;
    }

    private static ZonedDateTime parseConfirmedAt(String text) {
        Matcher matcher = CONFIRMED_AT.matcher(text);
        if (!matcher.matches()) {
            throw invalid("confirmedAt 은 UTC Z·소수초 최대 6자리 ISO-8601 이어야 합니다.");
        }
        String fraction = matcher.group(7) == null ? "" : matcher.group(7);
        int nanos = fraction.isEmpty() ? 0 : Integer.parseInt((fraction + "000000").substring(0, 6)) * 1_000;
        try {
            LocalDateTime local = LocalDateTime.of(
                Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4)),
                Integer.parseInt(matcher.group(5)), Integer.parseInt(matcher.group(6)), nanos);
            return ZonedDateTime.of(local, ZoneOffset.UTC);
        } catch (DateTimeException outOfRange) {
            throw invalid("confirmedAt 이 존재하지 않는 날짜·시각입니다.");
        }
    }

    private static JsonNode required(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            throw invalid("필수 필드가 없거나 null 입니다: " + field);
        }
        return node;
    }

    private static String requiredText(JsonNode parent, String field) {
        JsonNode node = required(parent, field);
        if (!node.isTextual()) {
            throw invalid("문자열이어야 합니다: " + field);
        }
        return node.textValue();
    }

    /** 실제 JSON 정수만 받는다. 문자열 숫자·소수·지수 표기는 자동 변환하지 않는다. */
    private static long requiredLong(JsonNode parent, String field) {
        JsonNode node = required(parent, field);
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw invalid("64bit 정수여야 합니다: " + field);
        }
        return node.longValue();
    }

    private static long requiredPositiveLong(JsonNode parent, String field) {
        long value = requiredLong(parent, field);
        if (value < 1L) {
            throw invalid("양수여야 합니다: " + field);
        }
        return value;
    }

    private static PurchaseActivityException invalid(String message) {
        return new PurchaseActivityException(PurchaseActivityErrorCode.INVALID_EVENT, message);
    }
}

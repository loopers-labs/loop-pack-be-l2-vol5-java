package com.loopers.infrastructure.order;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderOutboxMessage;
import com.loopers.domain.order.OrderOutboxMessageException;
import com.loopers.domain.order.OrderOutboxMessageException.Code;
import com.loopers.domain.order.OrderOutboxMessageVerifier;
import com.loopers.domain.order.OrderOutboxStoredMessage;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 저장된 OrderConfirmed v1 원문의 엄격 검증·정규화(설계 W7.3-6). 기존 소비 계약과 같은 규칙을 API 안에서 따로 구현하며 streamer 코드에 의존하지 않는다.
 *
 * <p>앱 공통 ObjectMapper 는 단일 값의 배열 수용 등 관대한 변환을 하므로 쓰지 않는다. 기본 설정의 전용 mapper 로 트리만 읽고
 * 중복 key 는 parser 가, 뒤따르는 token 은 여기서 거절한다. 숫자는 실제 JSON 정수 node 만 받는다.
 * 알려진 위반만 안전 code 예외로 바꾸고, 그 밖의 예외는 그대로 전파한다.
 */
@Component
public class JacksonOrderOutboxMessageVerifier implements OrderOutboxMessageVerifier {

    private static final String EVENT_ID_PREFIX = "order-confirmed:";
    /** UTC Z·초 필수·소수초 최대 6자리. 연도 4자리 1000~9999 는 DB DATETIME(6) 범위와 같다. */
    private static final Pattern CONFIRMED_AT = Pattern.compile(
        "([1-9][0-9]{3})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.([0-9]{1,6}))?Z");

    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build());

    @Override
    public OrderConfirmed parseAndValidate(OrderOutboxStoredMessage stored) {
        Objects.requireNonNull(stored, "stored");
        // 메타데이터: 지원 형식 → 저장 identity → key → payload 존재 순이다.
        if (stored.eventType() == null || stored.schemaVersion() == null) {
            throw failure(Code.INVALID_OUTBOX_MESSAGE);
        }
        requireSupported(stored.eventType(), stored.schemaVersion());
        Long orderId = stored.orderId();
        if (orderId == null || orderId < 1L
            || !eventIdOf(orderId).equals(stored.eventId())
            || !Long.toString(orderId).equals(stored.messageKey())) {
            throw failure(Code.INVALID_OUTBOX_MESSAGE);
        }
        String payload = stored.payload();
        if (payload == null || payload.isBlank() || !StandardCharsets.UTF_8.newEncoder().canEncode(payload)) {
            throw failure(Code.INVALID_OUTBOX_MESSAGE);
        }

        JsonNode root = readSingleObject(payload);
        // 다른 형식의 본문을 v1 필드 규칙으로 판정하지 않도록 type·version 부터 본다.
        requireSupported(requiredText(root, "eventType"), requiredLong(root, "schemaVersion"));
        String eventId = requiredText(root, "eventId");
        long bodyOrderId = requiredPositiveLong(root, "orderId");
        ZonedDateTime confirmedAt = parseConfirmedAt(requiredText(root, "confirmedAt"));
        List<OrderConfirmed.Item> items = normalizedItems(required(root, "items"));
        if (bodyOrderId != orderId || !stored.eventId().equals(eventId)) {
            throw failure(Code.INVALID_OUTBOX_MESSAGE);
        }
        return new OrderConfirmed(orderId, confirmedAt, items);
    }

    @Override
    public void requireSameFact(OrderConfirmed expected, OrderOutboxStoredMessage stored) {
        Objects.requireNonNull(expected, "expected");
        OrderConfirmed actual = parseAndValidate(stored);
        if (expected.orderId() != actual.orderId()
            || !expected.confirmedAt().toInstant().equals(actual.confirmedAt().toInstant())
            || !normalize(expected.items()).equals(actual.items())) {
            throw failure(Code.OUTBOX_FACT_MISMATCH);
        }
    }

    private static void requireSupported(String eventType, long schemaVersion) {
        if (!OrderOutboxMessage.EVENT_TYPE.equals(eventType) || schemaVersion != OrderOutboxMessage.SCHEMA_VERSION) {
            throw failure(Code.UNSUPPORTED_SCHEMA);
        }
    }

    private JsonNode readSingleObject(String text) {
        try (JsonParser parser = mapper.createParser(text)) {
            JsonNode root = mapper.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                throw failure(Code.INVALID_OUTBOX_MESSAGE);
            }
            return root;
        } catch (IOException malformed) {
            // parser 메시지에는 원문 일부가 들어가므로 cause 로 잇지 않는다.
            throw failure(Code.INVALID_OUTBOX_MESSAGE);
        }
    }

    /** 원본 항목마다 양수를 확인한 뒤 상품별로 합산한다. 합계만 보면 같은 상품의 -1 과 3 이 2 로 가려진다. */
    private static List<OrderConfirmed.Item> normalizedItems(JsonNode itemsNode) {
        if (!itemsNode.isArray() || itemsNode.isEmpty()) {
            throw failure(Code.INVALID_OUTBOX_MESSAGE);
        }
        Map<Long, Long> quantities = new TreeMap<>();
        for (JsonNode element : itemsNode) {
            if (!element.isObject()) {
                throw failure(Code.INVALID_OUTBOX_MESSAGE);
            }
            long productId = requiredPositiveLong(element, "productId");
            long quantity = requiredPositiveLong(element, "quantity");
            try {
                quantities.merge(productId, quantity, Math::addExact);
            } catch (ArithmeticException overflow) {
                throw failure(Code.INVALID_OUTBOX_MESSAGE);
            }
        }
        return toItems(quantities);
    }

    /**
     * 기대 사건도 같은 기준으로 합산·정렬해 비교한다. 생성 경로가 이미 정규화했어도 비교가 그 전제에 기대지 않게 한다.
     * 기대 값의 overflow 는 저장 원문 오류가 아니라 호출자의 프로그램 오류이므로 안전 code 로 바꾸지 않는다.
     */
    private static List<OrderConfirmed.Item> normalize(List<OrderConfirmed.Item> items) {
        Map<Long, Long> quantities = new TreeMap<>();
        items.forEach(item -> quantities.merge(item.productId(), item.quantity(), Math::addExact));
        return toItems(quantities);
    }

    private static List<OrderConfirmed.Item> toItems(Map<Long, Long> quantities) {
        return quantities.entrySet().stream().map(entry -> new OrderConfirmed.Item(entry.getKey(), entry.getValue())).toList();
    }

    private static ZonedDateTime parseConfirmedAt(String text) {
        Matcher matcher = CONFIRMED_AT.matcher(text);
        if (!matcher.matches()) {
            throw failure(Code.INVALID_OUTBOX_MESSAGE);
        }
        String fraction = matcher.group(7) == null ? "" : matcher.group(7);
        int nanos = fraction.isEmpty() ? 0 : Integer.parseInt((fraction + "000000").substring(0, 6)) * 1_000;
        try {
            return ZonedDateTime.of(LocalDateTime.of(
                Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)),
                Integer.parseInt(matcher.group(4)), Integer.parseInt(matcher.group(5)), Integer.parseInt(matcher.group(6)),
                nanos), ZoneOffset.UTC);
        } catch (DateTimeException outOfRange) {
            throw failure(Code.INVALID_OUTBOX_MESSAGE);
        }
    }

    private static JsonNode required(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            throw failure(Code.INVALID_OUTBOX_MESSAGE);
        }
        return node;
    }

    private static String requiredText(JsonNode parent, String field) {
        JsonNode node = required(parent, field);
        if (!node.isTextual()) {
            throw failure(Code.INVALID_OUTBOX_MESSAGE);
        }
        return node.textValue();
    }

    /** 실제 JSON 정수만 받는다. 문자열 숫자·소수·지수 표기·long 범위 밖 값은 변환하지 않는다. */
    private static long requiredLong(JsonNode parent, String field) {
        JsonNode node = required(parent, field);
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw failure(Code.INVALID_OUTBOX_MESSAGE);
        }
        return node.longValue();
    }

    private static long requiredPositiveLong(JsonNode parent, String field) {
        long value = requiredLong(parent, field);
        if (value < 1L) {
            throw failure(Code.INVALID_OUTBOX_MESSAGE);
        }
        return value;
    }

    private static String eventIdOf(long orderId) {
        return EVENT_ID_PREFIX + Long.toString(orderId);
    }

    private static OrderOutboxMessageException failure(Code code) {
        return new OrderOutboxMessageException(code);
    }
}

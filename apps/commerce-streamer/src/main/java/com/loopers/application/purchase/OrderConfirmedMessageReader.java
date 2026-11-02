package com.loopers.application.purchase;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.purchase.OrderConfirmation;
import com.loopers.domain.purchase.OrderConfirmationViolation;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 받은 바이트를 읽어 계약 검사까지 마친 {@link OrderConfirmation}으로 만든다(docs/week6/decisions.md D-14, D-19).
 *
 * - 파싱은 deserializer가 아니라 여기서 한다. deserializer에서 실패하면 listener가 불리지 않고 error handler(멈춤)로 직행해
 *   깨진 기록 하나가 poison pill이 된다(D-14). 여기서 실패하면 계약 깨짐으로 기록하고 넘어갈 수 있다.
 * - JSON을 해석하는 일(형식)은 여기서, 이벤트의 약속 검사는 domain(OrderConfirmation.from)에서 한다.
 * - 칸의 타입이 다르면(숫자 자리에 문자열 등) 계약 깨짐이다. 계약에 없는 칸은 무시한다.
 * - Spring의 ObjectMapper 설정에 기대지 않는다 — 읽는 규칙이 다른 설정 파일에 달리지 않게 전용 인스턴스를 쓴다.
 */
@Component
public class OrderConfirmedMessageReader {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * @throws OrderConfirmationViolation 읽을 수 없거나 약속을 어기면
     */
    public OrderConfirmation read(String recordKey, byte[] value) {
        if (value == null) {
            throw new OrderConfirmationViolation("value 없음(null)");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(value);
        } catch (IOException e) {
            throw new OrderConfirmationViolation("JSON을 읽을 수 없음: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new OrderConfirmationViolation("JSON 객체가 아님");
        }
        return OrderConfirmation.from(recordKey, new OrderConfirmation.Draft(
            text(root, "eventType"),
            integer(root, "schemaVersion"),
            text(root, "eventId"),
            longValue(root, "orderId"),
            text(root, "confirmedAt"),
            items(root)));
    }

    private static List<OrderConfirmation.Draft.Item> items(JsonNode root) {
        JsonNode node = root.get("items");
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw new OrderConfirmationViolation("items가 배열이 아님");
        }
        List<OrderConfirmation.Draft.Item> items = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) {
                throw new OrderConfirmationViolation("품목이 JSON 객체가 아님");
            }
            items.add(new OrderConfirmation.Draft.Item(longValue(item, "productId"), integer(item, "quantity")));
        }
        return items;
    }

    private static String text(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new OrderConfirmationViolation(field + "가 문자열이 아님");
        }
        return node.asText();
    }

    private static Long longValue(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new OrderConfirmationViolation(field + "가 정수가 아님");
        }
        return node.asLong();
    }

    private static Integer integer(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new OrderConfirmationViolation(field + "가 정수가 아님");
        }
        return node.asInt();
    }
}

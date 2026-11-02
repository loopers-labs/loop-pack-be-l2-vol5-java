package com.loopers.purchaseactivity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.kafka.event.OrderConfirmed;
import com.loopers.purchaseactivity.application.OrderConfirmedParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

class OrderConfirmedParserTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final OrderConfirmedParser parser = new OrderConfirmedParser(objectMapper);

    @Test
    void 지원하지_않는_주문_확정_버전을_거절한다() throws Exception {
        // Arrange
        var event = OrderConfirmed.confirmed(42, Instant.parse("2026-09-07T01:00:00Z"),
                List.of(new OrderConfirmed.Item(7, 2)));
        ObjectNode json = objectMapper.valueToTree(event);
        json.put("schemaVersion", 2);
        byte[] payload = objectMapper.writeValueAsBytes(json);

        // Act + Assert
        assertThatThrownBy(() -> parser.parseAndValidate("42", payload))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"UnknownOrderEvent", "", " "})
    void OrderConfirmed가_아닌_eventType을_거절한다(String eventType) throws Exception {
        // Arrange
        var event = OrderConfirmed.confirmed(42, Instant.parse("2026-09-07T01:00:00Z"),
                List.of(new OrderConfirmed.Item(7, 2)));
        ObjectNode json = objectMapper.valueToTree(event);
        json.put("eventType", eventType);
        byte[] payload = objectMapper.writeValueAsBytes(json);

        // Act + Assert
        assertThatThrownBy(() -> parser.parseAndValidate("42", payload))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 정상_이벤트_뒤에_다른_JSON이_붙어_있으면_거절한다() throws Exception {
        // Arrange
        var event = OrderConfirmed.confirmed(42, Instant.parse("2026-09-07T01:00:00Z"),
                List.of(new OrderConfirmed.Item(7, 2)));
        byte[] payload = (objectMapper.writeValueAsString(event) + " {}").getBytes(StandardCharsets.UTF_8);

        // Act + Assert
        assertThatThrownBy(() -> parser.parseAndValidate("42", payload))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void JSON_속성과_품목_순서가_달라도_같은_구매_사실로_읽는다() {
        // Arrange
        String json = """
                {"items":[{"quantity":1,"productId":8},{"quantity":2,"productId":7}],
                "confirmedAt":"2026-09-07T01:00:00Z","orderId":42,
                "eventId":"order-confirmed:v1:42","schemaVersion":1,"eventType":"OrderConfirmed"}
                """;

        // Act
        var event = parser.parseAndValidate("42", json.getBytes(StandardCharsets.UTF_8));

        // Assert
        assertThat(event).isEqualTo(OrderConfirmed.confirmed(42, Instant.parse("2026-09-07T01:00:00Z"),
                List.of(new OrderConfirmed.Item(7, 2), new OrderConfirmed.Item(8, 1))));
    }

    @Test
    void Kafka_key가_주문_ID와_다르면_거절한다() throws Exception {
        // Arrange
        var event = OrderConfirmed.confirmed(42, Instant.parse("2026-09-07T01:00:00Z"),
                List.of(new OrderConfirmed.Item(7, 2)));
        byte[] json = objectMapper.writeValueAsBytes(event);

        // Act + Assert
        assertThatThrownBy(() -> parser.parseAndValidate("43", json))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("key");
    }

    @Test
    void JSON이_아니거나_null인_메시지를_구매_활동으로_읽지_않는다() {
        // Arrange
        byte[] invalidJson = "not-json".getBytes(StandardCharsets.UTF_8);

        // Act + Assert
        assertThatThrownBy(() -> parser.parseAndValidate("42", invalidJson))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parseAndValidate("42", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"schemaVersion, 1.9", "orderId, 42.9", "productId, 7.9", "quantity, 2.9"})
    void 정수_필드의_소수를_잘라서_다른_구매_사실로_저장하지_않는다(String field, double invalidValue) throws Exception {
        // Arrange
        var event = OrderConfirmed.confirmed(42, Instant.parse("2026-09-07T01:00:00Z"),
                List.of(new OrderConfirmed.Item(7, 2)));
        ObjectNode json = objectMapper.valueToTree(event);
        ObjectNode target = (field.equals("productId") || field.equals("quantity"))
                ? (ObjectNode) json.path("items").get(0) : json;
        target.put(field, invalidValue);
        byte[] payload = objectMapper.writeValueAsBytes(json);

        // Act + Assert
        assertThatThrownBy(() -> parser.parseAndValidate("42", payload))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"schemaVersion, 1", "orderId, 42", "productId, 7", "quantity, 2"})
    void 정수_필드를_문자열로_보내면_계약_오류로_거절한다(String field, String invalidValue) throws Exception {
        // Arrange
        var event = OrderConfirmed.confirmed(42, Instant.parse("2026-09-07T01:00:00Z"),
                List.of(new OrderConfirmed.Item(7, 2)));
        ObjectNode json = objectMapper.valueToTree(event);
        ObjectNode target = (field.equals("productId") || field.equals("quantity"))
                ? (ObjectNode) json.path("items").get(0) : json;
        target.put(field, invalidValue);
        byte[] payload = objectMapper.writeValueAsBytes(json);

        // Act + Assert
        assertThatThrownBy(() -> parser.parseAndValidate("42", payload))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

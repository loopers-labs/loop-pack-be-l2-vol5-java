package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.purchase.dto.SavePurchaseActivityCommand;
import com.loopers.domain.purchase.PurchaseLine;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderConfirmedMessageParserTest {

    private static final String VALID = """
        {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:42",
         "orderId":42,"confirmedAt":"2026-09-07T01:00:00Z",
         "items":[{"productId":8,"quantity":1},{"productId":7,"quantity":2}]}""";

    private final OrderConfirmedMessageParser parser = new OrderConfirmedMessageParser(new ObjectMapper());

    private static ConsumerRecord<String, byte[]> record(String key, String json) {
        return new ConsumerRecord<>("order-events", 0, 5L, key, json.getBytes(StandardCharsets.UTF_8));
    }

    @DisplayName("계약대로 온 record 는 eventId·orderId·확정 시각·품목으로 해석한다.")
    @Test
    void parsesValidRecord() {
        // act
        SavePurchaseActivityCommand command = parser.parse(record("42", VALID));

        // assert
        assertThat(command.eventId()).isEqualTo("order-confirmed:v1:42");
        assertThat(command.orderId()).isEqualTo(42L);
        assertThat(command.confirmedAt().toInstant()).isEqualTo(ZonedDateTime.parse("2026-09-07T01:00:00Z").toInstant());
        assertThat(command.lines()).containsExactlyInAnyOrder(new PurchaseLine(7L, 2), new PurchaseLine(8L, 1));
    }

    @DisplayName("계약을 어긴 record 는 건너뛰지 않고, 위치(topic-partition@offset)를 담은 InvalidOrderEventException 으로 거절한다.")
    @ParameterizedTest(name = "{0}")
    @MethodSource("violations")
    void rejectsContractViolation(String name, String key, String json) {
        assertThatThrownBy(() -> parser.parse(record(key, json)))
            .isInstanceOf(InvalidOrderEventException.class)
            .hasMessageContaining("order-events-0@5");
    }

    static Stream<Arguments> violations() {
        return Stream.of(
            Arguments.of("key 와 orderId 불일치", "43", VALID),
            Arguments.of("다른 eventType", "42", VALID.replace("OrderConfirmed", "OrderCancelled")),
            Arguments.of("지원하지 않는 schemaVersion", "42", VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":2")),
            Arguments.of("orderId 에서 유도하지 않은 eventId", "42", VALID.replace("order-confirmed:v1:42", "order-confirmed:v1:99")),
            Arguments.of("수량 0", "42", VALID.replace("\"quantity\":2", "\"quantity\":0")),
            Arguments.of("품목 없음", "42", VALID.replaceAll("\"items\":\\[.*\\]", "\"items\":[]")),
            Arguments.of("확정 시각 없음", "42", VALID.replace("\"confirmedAt\":\"2026-09-07T01:00:00Z\",", "")),
            Arguments.of("JSON 아님", "42", "not-json")
        );
    }
}

package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.dto.SavePurchaseActivityCommand;
import com.loopers.contract.order.OrderConfirmedItem;
import com.loopers.domain.purchase.PurchaseLine;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static com.loopers.support.kafka.OrderConfirmedMessages.CONFIRMED_AT;
import static com.loopers.support.kafka.OrderConfirmedMessages.bytes;
import static com.loopers.support.kafka.OrderConfirmedMessages.of;
import static com.loopers.support.kafka.OrderConfirmedMessages.validBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderConfirmedMessageParserTest {

    private final OrderConfirmedMessageParser parser = new OrderConfirmedMessageParser();

    private static ConsumerRecord<String, byte[]> record(String key, byte[] value) {
        return new ConsumerRecord<>("order-events", 0, 5L, key, value);
    }

    @DisplayName("계약대로 온 record 는 eventId·orderId·확정 시각·품목으로 해석한다.")
    @Test
    void parsesValidRecord() {
        // act
        SavePurchaseActivityCommand command = parser.parse(record("42", validBytes(42L)));

        // assert
        assertThat(command.eventId()).isEqualTo("order-confirmed:v1:42");
        assertThat(command.orderId()).isEqualTo(42L);
        assertThat(command.confirmedAt().toInstant()).isEqualTo(CONFIRMED_AT);
        assertThat(command.lines()).containsExactlyInAnyOrder(new PurchaseLine(7L, 2), new PurchaseLine(8L, 1));
    }

    @DisplayName("계약을 어긴 record 는 건너뛰지 않고, 위치(topic-partition@offset)를 담은 InvalidOrderEventException 으로 거절한다.")
    @ParameterizedTest(name = "{0}")
    @MethodSource("violations")
    void rejectsContractViolation(String name, String key, byte[] value) {
        assertThatThrownBy(() -> parser.parse(record(key, value)))
            .isInstanceOf(InvalidOrderEventException.class)
            .hasMessageContaining("order-events-0@5");
    }

    static Stream<Arguments> violations() {
        OrderConfirmedItem item = new OrderConfirmedItem(7L, 2);
        return Stream.of(
            Arguments.of("key 와 orderId 불일치", "43", validBytes(42L)),
            Arguments.of("orderId 에서 유도하지 않은 eventId", "42", bytes(of(42L, "order-confirmed:v1:99", item))),
            Arguments.of("수량 0", "42", bytes(of(42L, "order-confirmed:v1:42", new OrderConfirmedItem(7L, 0)))),
            Arguments.of("품목 없음", "42", bytes(of(42L, "order-confirmed:v1:42"))),
            Arguments.of("값 없음", "42", null),
            Arguments.of("Avro 아님", "42", "not-avro".getBytes(StandardCharsets.UTF_8))
        );
    }
}

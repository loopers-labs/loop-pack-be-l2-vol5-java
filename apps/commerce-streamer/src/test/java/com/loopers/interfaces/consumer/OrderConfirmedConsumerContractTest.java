package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.purchase.OrderConfirmationCommand;
import com.loopers.contract.OrderEventContract;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** consumer 쪽 계약: producer 계약 테스트가 확인한 공유 예시(order-confirmed-v1.json)를 parser가 그대로 읽는다. */
class OrderConfirmedConsumerContractTest {
    private final OrderConfirmedMessageParser parser = new OrderConfirmedMessageParser(new ObjectMapper());

    @Test
    void parsesSharedContract() {
        OrderConfirmationCommand command = parser.parse("42",
            OrderEventContract.ORDER_CONFIRMED_V1.getBytes(StandardCharsets.UTF_8));

        assertThat(command.eventId()).isEqualTo("order-confirmed:v1:42");
        assertThat(command.orderId()).isEqualTo(42L);
        assertThat(command.confirmedAt()).isEqualTo(Instant.parse("2026-10-29T01:00:00.123456Z"));
        assertThat(command.items()).containsExactly(
            new OrderConfirmationCommand.Item(7L, 5), new OrderConfirmationCommand.Item(8L, 3));
    }
}

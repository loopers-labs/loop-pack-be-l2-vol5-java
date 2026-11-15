package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderEventPublication;
import com.loopers.domain.order.OrderOutboxMessage;
import com.loopers.support.kafka.BrokerRecords;
import com.loopers.support.kafka.OutboxTestSenders;
import com.loopers.testcontainers.KafkaBrokerFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaOrderOutboxSenderTest {

    // W6 누락 재현의 사례 B에서 broker가 실제로 가졌던 값과 같은 모양 (docs/week6/omission-repro.md 3절)
    private static final String PAYLOAD =
        "{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,\"eventId\":\"order-confirmed:v1:2\",\"orderId\":2,"
            + "\"confirmedAt\":\"2026-09-07T01:00:00Z\",\"items\":[{\"productId\":1,\"quantity\":2}]}";

    private static final OrderOutboxMessage MESSAGE = new OrderOutboxMessage("order-confirmed:v1:2", 2L, "2", PAYLOAD, 1);

    @DisplayName("저장된 key·payload를 한 번 더 감싸지 않고 그대로 보내고, broker가 알려 준 위치를 돌려준다.")
    @Test
    void sendsStoredPayloadAsIs() {
        // arrange
        String topic = KafkaBrokerFixture.createTopic("order-outbox-sender", 1);

        // act
        OrderEventPublication publication = OutboxTestSenders.toBroker(topic).send(MESSAGE);

        // assert
        assertThat(publication).isEqualTo(new OrderEventPublication.Sent(topic, 0, 0));
        List<BrokerRecords.Record> records = BrokerRecords.readAll(topic);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).key()).isEqualTo("2");
        assertThat(records.get(0).value()).as("JSON 문자열 리터럴로 감싸지 않은 원래 값").isEqualTo(PAYLOAD);
    }

    @DisplayName("broker에 닿지 않으면 예외 없이 실패를 돌려준다.")
    @Test
    void returnsFailed_whenBrokerIsUnreachable() {
        // act
        OrderEventPublication publication = OutboxTestSenders.unreachable("order-events-unreachable").send(MESSAGE);

        // assert
        assertThat(publication).isInstanceOf(OrderEventPublication.Failed.class);
        assertThat(((OrderEventPublication.Failed) publication).reason()).doesNotContain(PAYLOAD);
    }
}

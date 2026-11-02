package com.loopers.purchase.adapter.in.kafka;

import com.loopers.purchase.domain.InvalidOrderConfirmedException;
import com.loopers.purchase.domain.OrderConfirmedMessage;
import com.loopers.support.StreamerIntegrationTestSupport;
import com.loopers.utils.KafkaTopicReader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R-6 파싱 계약 (W6 D-2). 앱 공용 Jackson은 느슨하게 바꿔 읽는다(실수 → 정수, 문자열 숫자, 단일 객체 → 배열).
 * 구매 사실은 바꿔 읽으면 조용히 틀린 값이 저장되므로, 이 parser는 형식이 다르면 읽지 않는다 — 컨텍스트의 실제 ObjectMapper로 확인한다.
 */
class OrderConfirmedMessageParserTest extends StreamerIntegrationTestSupport {

    private static final String HEAD = "{\"eventId\":\"evt-1\",\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,\"orderId\":42,"
        + "\"confirmedAt\":\"2026-10-28T14:03:12.123456+09:00\",\"items\":";

    @Autowired
    private OrderConfirmedMessageParser orderConfirmedMessageParser;

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("order-events", 0, 0L, "42", value);
    }

    @DisplayName("api와 함께 쓰는 견본(order-confirmed-v1.json)은 그대로 읽는다 — 주문 42, 7×2 · 8×1.")
    @Test
    void readsTheSharedFixture() {
        OrderConfirmedMessage message = orderConfirmedMessageParser.parse(record(KafkaTopicReader.orderConfirmedFixture()));

        assertThat(message.orderId()).isEqualTo(42L);
        assertThat(message.items()).containsExactly(new OrderConfirmedMessage.Item(7L, 2), new OrderConfirmedMessage.Item(8L, 1));
    }

    @DisplayName("수량이 실수(1.9)면 1로 바꾸지 않고 거절한다.")
    @Test
    void rejectsFractionalQuantity() {
        assertThatThrownBy(() -> orderConfirmedMessageParser.parse(record(HEAD + "[{\"productId\":7,\"quantity\":1.9}]}")))
            .isInstanceOf(InvalidOrderConfirmedException.class);
    }

    @DisplayName("숫자 자리에 문자열(\"7\", \"2\")이 오면 숫자로 바꾸지 않고 거절한다.")
    @Test
    void rejectsNumbersAsStrings() {
        assertThatThrownBy(() -> orderConfirmedMessageParser.parse(record(HEAD + "[{\"productId\":\"7\",\"quantity\":\"2\"}]}")))
            .isInstanceOf(InvalidOrderConfirmedException.class);
    }

    @DisplayName("items가 배열이 아니라 객체 하나면 1개짜리 목록으로 바꾸지 않고 거절한다.")
    @Test
    void rejectsSingleObjectItems() {
        assertThatThrownBy(() -> orderConfirmedMessageParser.parse(record(HEAD + "{\"productId\":7,\"quantity\":2}}")))
            .isInstanceOf(InvalidOrderConfirmedException.class);
    }
}

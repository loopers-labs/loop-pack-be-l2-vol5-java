package com.loopers.domain.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderOutboxModelTest {

    private static final Instant RECORDED_AT = Instant.parse("2026-09-07T01:00:00Z");
    private static final String PAYLOAD = "{\"eventId\":\"order-confirmed:v1:42\"}";

    private static OrderConfirmedEvent event() {
        return new OrderConfirmedEvent(
            OrderConfirmedEvent.EVENT_TYPE,
            OrderConfirmedEvent.SCHEMA_VERSION,
            OrderConfirmedEvent.eventIdOf(42L),
            42L,
            "2026-09-07T01:00:00Z",
            List.of(new OrderConfirmedEvent.Item(7L, 2), new OrderConfirmedEvent.Item(8L, 1))
        );
    }

    @DisplayName("PENDING 행을 만들면, ")
    @Nested
    class Pending {

        @DisplayName("이벤트의 식별·key·계약과 받은 payload를 그대로 담고, 시도 0회로 기록 시각부터 relay 대상이다.")
        @Test
        void copiesEventIdentityAndStartsAsPending() {
            // act
            OrderOutboxModel outbox = OrderOutboxModel.pending(event(), PAYLOAD, RECORDED_AT);

            // assert
            assertThat(outbox.getEventId()).isEqualTo("order-confirmed:v1:42");
            assertThat(outbox.getOrderId()).isEqualTo(42L);
            assertThat(outbox.getEventType()).isEqualTo("OrderConfirmed");
            assertThat(outbox.getSchemaVersion()).isEqualTo(1);
            assertThat(outbox.getMessageKey()).as("W6와 같은 Kafka key").isEqualTo("42");
            assertThat(outbox.getPayload()).isEqualTo(PAYLOAD);
            assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
            assertThat(outbox.getAttemptCount()).isZero();
            assertThat(outbox.getNextAttemptAt()).isEqualTo(RECORDED_AT);
            assertThat(outbox.getLastError()).isNull();
            assertThat(outbox.getPublishedAt()).as("broker 확인 전에는 발행 시각이 없다").isNull();
        }

        @DisplayName("이벤트·payload·기록 시각이 비어 있으면 만들지 않는다.")
        @Test
        void rejectsMissingValues() {
            assertThatThrownBy(() -> OrderOutboxModel.pending(null, PAYLOAD, RECORDED_AT)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> OrderOutboxModel.pending(event(), null, RECORDED_AT)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> OrderOutboxModel.pending(event(), " ", RECORDED_AT)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> OrderOutboxModel.pending(event(), PAYLOAD, null)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}

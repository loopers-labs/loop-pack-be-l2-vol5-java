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

    @DisplayName("relay가 시도를 기록할 때, ")
    @Nested
    class Relay {

        private static final Instant RETRY_AT = RECORDED_AT.plusSeconds(1);

        @DisplayName("시도를 시작할 때마다 시도 수가 늘고, broker 확인을 저장하면 PUBLISHED가 되어 더는 시도하지 않는다.")
        @Test
        void countsAttempts_andStopsAfterPublished() {
            // arrange
            OrderOutboxModel outbox = OrderOutboxModel.pending(event(), PAYLOAD, RECORDED_AT);

            // act
            boolean first = outbox.startAttempt();
            boolean second = outbox.startAttempt();
            boolean published = outbox.markPublished(RETRY_AT);

            // assert
            assertThat(first).isTrue();
            assertThat(second).isTrue();
            assertThat(published).isTrue();
            assertThat(outbox.getAttemptCount()).isEqualTo(2);
            assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
            assertThat(outbox.getPublishedAt()).isEqualTo(RETRY_AT);
            assertThat(outbox.startAttempt()).as("PUBLISHED 행은 다시 시도하지 않는다").isFalse();
            assertThat(outbox.markPublished(RETRY_AT.plusSeconds(9))).as("처음 확인한 시각을 지킨다").isFalse();
            assertThat(outbox.getPublishedAt()).isEqualTo(RETRY_AT);
            assertThat(outbox.getAttemptCount()).isEqualTo(2);
        }

        @DisplayName("실패한 시도는 PENDING을 유지한 채 다음 시도 시각과 오류를 남긴다.")
        @Test
        void keepsPendingWithNextAttemptAndError_whenAttemptFails() {
            // arrange
            OrderOutboxModel outbox = OrderOutboxModel.pending(event(), PAYLOAD, RECORDED_AT);
            outbox.startAttempt();

            // act
            boolean recorded = outbox.recordFailure(1, "TimeoutException: broker 확인 없음", RETRY_AT);

            // assert
            assertThat(recorded).isTrue();
            assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
            assertThat(outbox.getNextAttemptAt()).isEqualTo(RETRY_AT);
            assertThat(outbox.getLastError()).isEqualTo("TimeoutException: broker 확인 없음");
        }

        @DisplayName("이전 시도의 늦은 실패나 PUBLISHED 뒤의 실패는 상태를 바꾸지 못한다.")
        @Test
        void ignoresStaleOrLateFailures() {
            // arrange
            OrderOutboxModel outbox = OrderOutboxModel.pending(event(), PAYLOAD, RECORDED_AT);
            outbox.startAttempt();
            outbox.startAttempt();

            // act & assert: 2번째 시도 중에 1번째 시도의 실패가 늦게 도착한다
            assertThat(outbox.recordFailure(1, "늦은 실패", RETRY_AT)).isFalse();
            assertThat(outbox.getNextAttemptAt()).isEqualTo(RECORDED_AT);
            assertThat(outbox.getLastError()).isNull();

            // act & assert: 확인을 저장한 뒤 도착한 실패
            outbox.markPublished(RETRY_AT);
            assertThat(outbox.recordFailure(2, "늦은 실패", RETRY_AT.plusSeconds(1))).isFalse();
            assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
        }

        @DisplayName("오류 메시지는 열 길이까지만 남긴다.")
        @Test
        void truncatesLongError() {
            // arrange
            OrderOutboxModel outbox = OrderOutboxModel.pending(event(), PAYLOAD, RECORDED_AT);
            outbox.startAttempt();

            // act
            outbox.recordFailure(1, "x".repeat(OrderOutboxModel.MAX_ERROR_LENGTH + 100), RETRY_AT);

            // assert
            assertThat(outbox.getLastError()).hasSize(OrderOutboxModel.MAX_ERROR_LENGTH);
        }
    }

    @DisplayName("누락 복구가 기존 행을 확인할 때, ")
    @Nested
    class SameFact {

        @DisplayName("식별 정보와 payload가 모두 같으면 같은 사실이다. 상태·시도 수는 보지 않는다.")
        @Test
        void sameFact_regardlessOfRelayState() {
            // arrange
            OrderOutboxModel outbox = OrderOutboxModel.pending(event(), PAYLOAD, RECORDED_AT);
            outbox.startAttempt();
            outbox.markPublished(RECORDED_AT.plusSeconds(1));

            // act & assert
            assertThat(outbox.holdsSameFact(event(), PAYLOAD)).isTrue();
        }

        @DisplayName("payload나 사건 식별이 하나라도 다르면 같은 사실이 아니다.")
        @Test
        void differentFact_whenPayloadOrIdentityDiffers() {
            // arrange
            OrderOutboxModel outbox = OrderOutboxModel.pending(event(), PAYLOAD, RECORDED_AT);
            OrderConfirmedEvent otherOrder = new OrderConfirmedEvent(
                OrderConfirmedEvent.EVENT_TYPE, OrderConfirmedEvent.SCHEMA_VERSION, OrderConfirmedEvent.eventIdOf(43L), 43L,
                "2026-09-07T01:00:00Z", event().items()
            );

            // act & assert
            assertThat(outbox.holdsSameFact(event(), "{\"eventId\":\"order-confirmed:v1:42\",\"items\":[]}")).isFalse();
            assertThat(outbox.holdsSameFact(otherOrder, PAYLOAD)).isFalse();
        }
    }
}

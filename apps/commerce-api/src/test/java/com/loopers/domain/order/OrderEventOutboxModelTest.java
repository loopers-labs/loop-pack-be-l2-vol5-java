package com.loopers.domain.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AG-06 포함 개념 주문 이벤트 Outbox (TB-14). */
class OrderEventOutboxModelTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-09-07T10:00:00.123456+09:00[Asia/Seoul]");
    private static final String PAYLOAD = "{\"eventId\":\"order-confirmed:v1:1\"}";

    @DisplayName("새 행은 PENDING, 시도·실패 0, availableAt 부터 전송 대상이고 발행 확인이 없다. 사건·전송 내용은 받은 그대로다.")
    @Test
    void pending_startsAsPending() {
        OrderEventOutboxModel outbox = OrderEventOutboxModel.pending("order-confirmed:v1:1", 1L, "OrderConfirmed", 1, "1", PAYLOAD, NOW);

        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getAttemptCount()).isZero();
        assertThat(outbox.getFailureCount()).isZero();
        assertThat(outbox.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(outbox.getLastError()).isNull();
        assertThat(outbox.getPublishedAt()).isNull();
        assertThat(outbox.getPublishedPartition()).isNull();
        assertThat(outbox.getPublishedOffset()).isNull();
        assertThat(outbox.getEventId()).isEqualTo("order-confirmed:v1:1");
        assertThat(outbox.getOrderId()).isEqualTo(1L);
        assertThat(outbox.getEventType()).isEqualTo("OrderConfirmed");
        assertThat(outbox.getSchemaVersion()).isEqualTo(1);
        assertThat(outbox.getMessageKey()).isEqualTo("1");
        assertThat(outbox.getPayload()).isEqualTo(PAYLOAD);
    }

    @DisplayName("eventId·eventType·messageKey·payload 가 비어 있으면 만들지 않는다.")
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void pending_rejectsBlankText(String blank) {
        assertThatThrownBy(() -> OrderEventOutboxModel.pending(blank, 1L, "OrderConfirmed", 1, "1", PAYLOAD, NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderEventOutboxModel.pending("e", 1L, blank, 1, "1", PAYLOAD, NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderEventOutboxModel.pending("e", 1L, "OrderConfirmed", 1, blank, PAYLOAD, NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderEventOutboxModel.pending("e", 1L, "OrderConfirmed", 1, "1", blank, NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("orderId·availableAt 이 없으면 만들지 않는다.")
    @Test
    void pending_rejectsMissingOrderOrTime() {
        assertThatThrownBy(() -> OrderEventOutboxModel.pending("e", null, "OrderConfirmed", 1, "1", PAYLOAD, NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderEventOutboxModel.pending("e", 1L, "OrderConfirmed", 1, "1", PAYLOAD, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static OrderEventOutboxModel pending() {
        return OrderEventOutboxModel.pending("order-confirmed:v1:1", 1L, "OrderConfirmed", 1, "1", PAYLOAD, NOW);
    }

    @DisplayName("전송을 시작하면 시도 횟수가 오르고, 결과를 저장하지 못하고 멈췄을 때를 대비해 재시도 시각을 lease 뒤로 미룬다.")
    @Test
    void startAttempt_countsAttempt_andLeasesRow() {
        OrderEventOutboxModel outbox = pending();

        outbox.startAttempt(NOW, Duration.ofSeconds(35));

        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getAttemptCount()).isEqualTo(1);
        assertThat(outbox.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(35));
    }

    @DisplayName("broker 확인을 저장하면 PUBLISHED, 확인 저장 시각·기록 위치가 남고 사건·전송 내용은 그대로다. 다시 보내거나 실패로 바꿀 수 없다.")
    @Test
    void markPublished_recordsBrokerAck() {
        OrderEventOutboxModel outbox = pending();
        outbox.startAttempt(NOW, Duration.ofSeconds(35));

        outbox.markPublished(NOW.plusSeconds(1), 2, 7L);

        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(outbox.getPublishedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(outbox.getPublishedPartition()).isEqualTo(2);
        assertThat(outbox.getPublishedOffset()).isEqualTo(7L);
        assertThat(outbox.getPayload()).isEqualTo(PAYLOAD);
        assertThatThrownBy(() -> outbox.startAttempt(NOW, Duration.ofSeconds(1))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> outbox.recordFailure(NOW, "e", Duration.ofSeconds(1), 3)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> outbox.resume(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @DisplayName("실패는 연속 실패 횟수·마지막 오류를 남기고 재시도 시각을 늦춘다. 연속 실패가 상한에 닿으면 BLOCKED 이고 재시도 시각을 바꾸지 않는다.")
    @Test
    void recordFailure_blocksAtMaxFailures() {
        OrderEventOutboxModel outbox = pending();

        outbox.startAttempt(NOW, Duration.ofSeconds(35));
        outbox.recordFailure(NOW, "TimeoutException: x", Duration.ofSeconds(1), 3);
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getFailureCount()).isEqualTo(1);
        assertThat(outbox.getLastError()).isEqualTo("TimeoutException: x");
        assertThat(outbox.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));

        outbox.startAttempt(NOW.plusSeconds(1), Duration.ofSeconds(35));
        outbox.recordFailure(NOW.plusSeconds(1), "e2", Duration.ofSeconds(2), 3);
        outbox.startAttempt(NOW.plusSeconds(3), Duration.ofSeconds(35));
        outbox.recordFailure(NOW.plusSeconds(3), "e3", Duration.ofSeconds(4), 3);

        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.BLOCKED);
        assertThat(outbox.isBlocked()).isTrue();
        assertThat(outbox.getFailureCount()).isEqualTo(3);
        assertThat(outbox.getAttemptCount()).isEqualTo(3);
        assertThat(outbox.getLastError()).isEqualTo("e3");
        assertThatThrownBy(() -> outbox.startAttempt(NOW, Duration.ofSeconds(1))).isInstanceOf(IllegalStateException.class);
    }

    @DisplayName("마지막 오류는 500자로 자른다.")
    @Test
    void recordFailure_truncatesError() {
        OrderEventOutboxModel outbox = pending();
        outbox.startAttempt(NOW, Duration.ofSeconds(35));

        outbox.recordFailure(NOW, "x".repeat(600), Duration.ofSeconds(1), 3);

        assertThat(outbox.getLastError()).hasSize(500);
    }

    @DisplayName("BLOCKED 를 재개하면 같은 행이 바로 보낼 수 있는 PENDING 이 된다. 연속 실패만 0 이고 누적 시도·마지막 오류는 남는다. PENDING 은 재개할 수 없다.")
    @Test
    void resume_returnsBlockedRowToPending() {
        OrderEventOutboxModel outbox = pending();
        assertThatThrownBy(() -> outbox.resume(NOW)).isInstanceOf(IllegalStateException.class);
        for (int i = 0; i < 3; i++) {
            outbox.startAttempt(NOW, Duration.ofSeconds(35));
            outbox.recordFailure(NOW, "e" + i, Duration.ofSeconds(1), 3);
        }

        outbox.resume(NOW.plusHours(1));

        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getFailureCount()).isZero();
        assertThat(outbox.getAttemptCount()).isEqualTo(3);
        assertThat(outbox.getLastError()).isEqualTo("e2");
        assertThat(outbox.getNextAttemptAt()).isEqualTo(NOW.plusHours(1));
    }
}

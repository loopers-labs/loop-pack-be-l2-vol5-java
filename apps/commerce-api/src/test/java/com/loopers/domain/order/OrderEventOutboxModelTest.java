package com.loopers.domain.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

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
}

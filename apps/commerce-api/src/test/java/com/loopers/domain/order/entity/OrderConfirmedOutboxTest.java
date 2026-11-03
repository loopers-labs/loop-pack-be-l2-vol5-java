package com.loopers.domain.order.entity;

import com.loopers.domain.order.enums.OrderOutboxStatus;
import com.loopers.domain.order.vo.OrderConfirmed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

class OrderConfirmedOutboxTest {

    private static final OrderConfirmed EVENT = new OrderConfirmed("order-confirmed:v1:42", 42L,
        ZonedDateTime.parse("2026-09-07T01:00:00.123456Z"), List.of(new OrderConfirmed.Item(7L, 2)));

    @DisplayName("확정 사실로 만들면, 같은 identity 와 주문 key 를 갖고 바로 보낼 수 있는 PENDING 이 된다. (7주차 설계 3.1)")
    @Test
    void createsPendingFromConfirmedFact() {
        ZonedDateTime now = ZonedDateTime.parse("2026-09-07T10:00:00+09:00[Asia/Seoul]");

        OrderConfirmedOutbox outbox = OrderConfirmedOutbox.pending(EVENT, "{}", now);

        assertAll(
            () -> assertThat(outbox.getEventId()).isEqualTo("order-confirmed:v1:42"),
            () -> assertThat(outbox.getOrderId()).isEqualTo(42L),
            () -> assertThat(outbox.getMessageKey()).isEqualTo("42"),
            () -> assertThat(outbox.getSchemaVersion()).isEqualTo(1),
            () -> assertThat(outbox.getPayload()).isEqualTo("{}"),
            () -> assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.PENDING),
            () -> assertThat(outbox.getAttemptCount()).isZero(),
            () -> assertThat(outbox.getNextAttemptAt()).isEqualTo(now),
            () -> assertThat(outbox.getLastError()).isNull(),
            () -> assertThat(outbox.getPublishedAt()).isNull()
        );
    }

    @DisplayName("다음 시도 시각은 DB 와 같은 마이크로초로 잘라, 저장 전후 값이 같다.")
    @Test
    void truncatesNextAttemptAtToMicros() {
        ZonedDateTime now = ZonedDateTime.parse("2026-09-07T01:00:00.123456789Z");

        OrderConfirmedOutbox outbox = OrderConfirmedOutbox.pending(EVENT, "{}", now);

        assertThat(outbox.getNextAttemptAt()).isEqualTo(ZonedDateTime.parse("2026-09-07T01:00:00.123456Z"));
    }
}

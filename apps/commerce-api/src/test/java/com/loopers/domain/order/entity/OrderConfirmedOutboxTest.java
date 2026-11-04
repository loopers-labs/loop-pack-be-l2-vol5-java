package com.loopers.domain.order.entity;

import com.loopers.domain.order.enums.OrderOutboxStatus;
import com.loopers.domain.order.vo.OrderConfirmed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderConfirmedOutboxTest {

    private static final OrderConfirmed EVENT = new OrderConfirmed("order-confirmed:v1:42", 42L,
        ZonedDateTime.parse("2026-09-07T01:00:00.123456Z"), List.of(new OrderConfirmed.Item(7L, 2)));
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-09-07T10:00:00+09:00[Asia/Seoul]");

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

    @DisplayName("시도를 시작하면 시도 횟수를 올리고 다음 시각을 lease 만큼 미뤄, 그 사이에는 차례가 아니다. (7주차 D-104)")
    @Test
    void startsAttemptWithLease() {
        OrderConfirmedOutbox outbox = OrderConfirmedOutbox.pending(EVENT, "{}", NOW);

        outbox.startAttempt(NOW, Duration.ofSeconds(30));

        assertAll(
            () -> assertThat(outbox.getAttemptCount()).isEqualTo(1),
            () -> assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.PENDING),
            () -> assertThat(outbox.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30)),
            () -> assertThat(outbox.isDue(NOW.plusSeconds(29))).isFalse(),
            () -> assertThat(outbox.isDue(NOW.plusSeconds(30))).isTrue()
        );
    }

    @DisplayName("broker 확인을 저장하면 PUBLISHED 가 되고 더는 차례가 아니다. (D-105)")
    @Test
    void marksPublished() {
        OrderConfirmedOutbox outbox = OrderConfirmedOutbox.pending(EVENT, "{}", NOW);
        outbox.startAttempt(NOW, Duration.ofSeconds(30));

        outbox.markPublished(NOW.plusSeconds(1));

        assertAll(
            () -> assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED),
            () -> assertThat(outbox.getPublishedAt()).isEqualTo(NOW.plusSeconds(1)),
            () -> assertThat(outbox.isDue(NOW.plusYears(1))).isFalse()
        );
    }

    @DisplayName("일시적 실패면 PENDING 그대로 다음 시각과 오류를 남긴다. (D-105, D-106)")
    @Test
    void retriesLater() {
        OrderConfirmedOutbox outbox = OrderConfirmedOutbox.pending(EVENT, "{}", NOW);
        outbox.startAttempt(NOW, Duration.ofSeconds(30));

        outbox.retryAt(NOW.plusSeconds(1), "TimeoutException: broker 없음");

        assertAll(
            () -> assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.PENDING),
            () -> assertThat(outbox.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1)),
            () -> assertThat(outbox.getLastError()).isEqualTo("TimeoutException: broker 없음"),
            () -> assertThat(outbox.getAttemptCount()).isEqualTo(1)
        );
    }

    @DisplayName("영구 실패면 BLOCKED 가 되어 더는 차례가 아니고, 오류는 1000자로 잘라 남긴다. (D-106)")
    @Test
    void blocksWithTruncatedError() {
        OrderConfirmedOutbox outbox = OrderConfirmedOutbox.pending(EVENT, "{}", NOW);
        outbox.startAttempt(NOW, Duration.ofSeconds(30));

        outbox.block("x".repeat(1_500));

        assertAll(
            () -> assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.BLOCKED),
            () -> assertThat(outbox.getLastError()).hasSize(1_000),
            () -> assertThat(outbox.isDue(NOW.plusYears(1))).isFalse()
        );
    }

    @DisplayName("PENDING 이 아닌 행은 시도 · 결과 저장을 거절한다.")
    @Test
    void rejectsTransition_whenNotPending() {
        OrderConfirmedOutbox outbox = OrderConfirmedOutbox.pending(EVENT, "{}", NOW);
        outbox.startAttempt(NOW, Duration.ofSeconds(30));
        outbox.markPublished(NOW);

        assertAll(
            () -> assertThrows(IllegalStateException.class, () -> outbox.startAttempt(NOW, Duration.ofSeconds(30))),
            () -> assertThrows(IllegalStateException.class, () -> outbox.markPublished(NOW)),
            () -> assertThrows(IllegalStateException.class, () -> outbox.retryAt(NOW, "e")),
            () -> assertThrows(IllegalStateException.class, () -> outbox.block("e"))
        );
    }
}

package com.loopers.application.order.facade;

import com.loopers.domain.order.entity.OrderConfirmedOutbox;
import com.loopers.domain.order.enums.OrderOutboxStatus;
import com.loopers.domain.order.repository.FakeOrderConfirmedOutboxRepository;
import com.loopers.domain.order.vo.OrderConfirmed;
import com.loopers.domain.order.vo.OrderConfirmedSendResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/** 회차의 판단(멈춤 · 계속 · backoff)을 시각을 통제해 확인함. 실제 DB · broker 장면은 OrderOutboxRelayTest */
class OrderOutboxRelayRoundTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-11-04T10:00:00+09:00[Asia/Seoul]");
    private static final OrderOutboxRelayProperties PROPERTIES =
        new OrderOutboxRelayProperties(50, Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofMinutes(5));

    private FakeOrderConfirmedOutboxRepository outboxRepository;
    private MutableClock clock;
    private Deque<OrderConfirmedSendResult> results;
    private List<String> sentKeys;
    private OrderOutboxRelay relay;

    @BeforeEach
    void setUp() {
        outboxRepository = new FakeOrderConfirmedOutboxRepository();
        clock = new MutableClock(NOW.toInstant(), NOW.getZone());
        results = new ArrayDeque<>();
        sentKeys = new ArrayList<>();
        relay = new OrderOutboxRelay(new OrderOutboxRelaySteps(outboxRepository), (key, payload) -> {
            sentKeys.add(key);
            return results.isEmpty() ? new OrderConfirmedSendResult.Acked("order-events", 0, sentKeys.size()) : results.poll();
        }, clock, PROPERTIES);
    }

    private OrderConfirmedOutbox record(long orderId) {
        OrderConfirmed event = new OrderConfirmed("order-confirmed:v1:" + orderId, orderId, NOW, List.of(new OrderConfirmed.Item(7L, 1)));
        return outboxRepository.save(OrderConfirmedOutbox.pending(event, "{}", NOW));
    }

    @DisplayName("차례인 행을 id 순으로 보내고 PUBLISHED 로 저장한다.")
    @Test
    void publishesDueRowsInIdOrder() {
        OrderConfirmedOutbox first = record(1L);
        OrderConfirmedOutbox second = record(2L);

        boolean ran = relay.runOnce();

        assertAll(
            () -> assertThat(ran).isTrue(),
            () -> assertThat(sentKeys).containsExactly("1", "2"),
            () -> assertThat(first.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED),
            () -> assertThat(first.getPublishedAt()).isEqualTo(NOW),
            () -> assertThat(second.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED)
        );
    }

    @DisplayName("일시적 실패면 그 행에 backoff 를 걸고 회차를 멈춰, 남은 행은 시도 횟수도 늘지 않는다. (D-104)")
    @Test
    void stopsRound_whenTransientFailure() {
        OrderConfirmedOutbox first = record(1L);
        OrderConfirmedOutbox second = record(2L);
        results.add(new OrderConfirmedSendResult.Transient("TimeoutException: broker 없음"));

        relay.runOnce();

        assertAll(
            () -> assertThat(sentKeys).containsExactly("1"),
            () -> assertThat(first.getStatus()).isEqualTo(OrderOutboxStatus.PENDING),
            () -> assertThat(first.getAttemptCount()).isEqualTo(1),
            () -> assertThat(first.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1)),
            () -> assertThat(first.getLastError()).isEqualTo("TimeoutException: broker 없음"),
            () -> assertThat(second.getStatus()).isEqualTo(OrderOutboxStatus.PENDING),
            () -> assertThat(second.getAttemptCount()).isZero()
        );
    }

    @DisplayName("backoff 시각 전에는 고르지 않고, 지나면 다시 보내며 실패할수록 두 배로 늘린다.")
    @Test
    void retriesAfterBackoff_withDoublingDelay() {
        OrderConfirmedOutbox outbox = record(1L);
        results.add(new OrderConfirmedSendResult.Transient("첫 실패"));
        relay.runOnce();

        clock.advance(Duration.ofMillis(999));
        relay.runOnce();
        int attemptsBeforeDue = outbox.getAttemptCount();

        clock.advance(Duration.ofMillis(1));
        results.add(new OrderConfirmedSendResult.Transient("두 번째 실패"));
        relay.runOnce();

        assertAll(
            () -> assertThat(attemptsBeforeDue).isEqualTo(1),
            () -> assertThat(outbox.getAttemptCount()).isEqualTo(2),
            () -> assertThat(outbox.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1).plusSeconds(2)),
            () -> assertThat(outbox.getLastError()).isEqualTo("두 번째 실패")
        );
    }

    @DisplayName("backoff 는 1s 에서 두 배씩 늘고 상한 5분을 넘지 않으며, 시도 횟수가 커도 넘치지 않는다. (D-106, D-108)")
    @Test
    void capsBackoff() {
        assertAll(
            () -> assertThat(relay.backoff(1)).isEqualTo(Duration.ofSeconds(1)),
            () -> assertThat(relay.backoff(2)).isEqualTo(Duration.ofSeconds(2)),
            () -> assertThat(relay.backoff(9)).isEqualTo(Duration.ofSeconds(256)),
            () -> assertThat(relay.backoff(10)).isEqualTo(Duration.ofMinutes(5)),
            () -> assertThat(relay.backoff(10_000)).isEqualTo(Duration.ofMinutes(5))
        );
    }

    @DisplayName("영구 실패면 그 행만 BLOCKED 로 격리하고 회차는 다음 행으로 이어 간다. BLOCKED 는 다시 고르지 않는다. (D-106)")
    @Test
    void blocksAndContinues_whenPermanentFailure() {
        OrderConfirmedOutbox first = record(1L);
        OrderConfirmedOutbox second = record(2L);
        results.add(new OrderConfirmedSendResult.Permanent("RecordTooLargeException: 너무 큼"));

        relay.runOnce();
        clock.advance(Duration.ofDays(1));
        relay.runOnce();

        assertAll(
            () -> assertThat(sentKeys).containsExactly("1", "2"),
            () -> assertThat(first.getStatus()).isEqualTo(OrderOutboxStatus.BLOCKED),
            () -> assertThat(first.getLastError()).isEqualTo("RecordTooLargeException: 너무 큼"),
            () -> assertThat(second.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED)
        );
    }

    @DisplayName("한 회차는 묶음 크기만큼만 고른다.")
    @Test
    void limitsBatch() {
        OrderOutboxRelay smallBatch = new OrderOutboxRelay(new OrderOutboxRelaySteps(outboxRepository), (key, payload) -> {
            sentKeys.add(key);
            return new OrderConfirmedSendResult.Acked("order-events", 0, 0);
        }, clock, new OrderOutboxRelayProperties(2, PROPERTIES.lease(), PROPERTIES.backoffInitial(), PROPERTIES.backoffMax()));
        record(1L);
        record(2L);
        record(3L);

        smallBatch.runOnce();

        assertThat(sentKeys).containsExactly("1", "2");
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

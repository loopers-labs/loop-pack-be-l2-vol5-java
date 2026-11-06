package com.loopers.application.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class OrderOutboxRelayTest {
    @Test
    void 수동_재개는_pending으로만_바꾸고_다음_조회에서_같은_내용을_발행한다() {
        var clock = new AdjustableClock();
        var store = new MemoryStore();
        var sent = new ArrayList<String>();
        var brokerUnavailable = new AtomicBoolean(true);
        OutboxKafkaSender sender = (topic, key, payload) -> {
            sent.add(key + ":" + payload);
            if (brokerUnavailable.get()) {
                throw new IllegalStateException("broker unavailable");
            }
        };
        var relay = new OrderOutboxRelay(store, sender, clock, bound -> 0);
        assertThat(store.status).isEqualTo("PENDING");
        assertThat(store.totalAttempts).isZero();
        assertThat(sent).isEmpty();

        for (int i = 0; i < 3; i++) {
            relay.runOnce();
            clock.advance(Duration.ofMinutes(1));
        }
        relay.runOnce();

        assertThat(store.status).isEqualTo("BLOCKED");
        assertThat(store.totalAttempts).isEqualTo(3);
        assertThat(sent).hasSize(3);
        assertThat(store.nextAttemptAt).isNull();

        brokerUnavailable.set(false);
        relay.resume("event-1");

        assertThat(store.status).isEqualTo("PENDING");
        assertThat(store.totalAttempts).isEqualTo(3);
        assertThat(sent).hasSize(3);

        relay.runOnce();

        assertThat(store.status).isEqualTo("PUBLISHED");
        assertThat(store.totalAttempts).isEqualTo(4);
        assertThat(sent).containsExactly("42:original", "42:original", "42:original", "42:original");
    }

    @Test
    void blocked가_아닌_이벤트는_수동_재개할_수_없다() {
        var store = new MemoryStore();
        var relay = new OrderOutboxRelay(store, (topic, key, payload) -> { }, new AdjustableClock(), bound -> 0);

        assertThatThrownBy(() -> relay.resume("event-1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Blocked");
        assertThat(store.status).isEqualTo("PENDING");

        store.status = "PUBLISHED";
        assertThatThrownBy(() -> relay.resume("event-1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Blocked");
        assertThat(store.status).isEqualTo("PUBLISHED");
    }

    @Test
    void 스케줄러_실행이_겹쳐도_동시에_발행하지_않는다() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var sends = new AtomicInteger();
        var store = new MemoryStore();
        var relay = new OrderOutboxRelay(store, (topic, key, payload) -> {
            sends.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("send was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }, new AdjustableClock(), bound -> 0);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(relay::runOnce);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            relay.runOnce();
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertThat(sends).hasValue(1);
            assertThat(store.status).isEqualTo("PUBLISHED");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void 발행중_스레드가_중단되면_나머지_배치의_시도횟수를_소비하지_않는다() {
        MemoryStore store = new MemoryStore() {
            @Override public List<String> readyEventIds(Instant now, int limit) {
                return List.of("event-1", "event-2");
            }
        };
        var relay = new OrderOutboxRelay(store, (topic, key, payload) -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }, new AdjustableClock(), bound -> 0);

        try {
            relay.runOnce();
            assertThat(store.totalAttempts).isEqualTo(1);
            assertThat(store.attemptedEventIds).containsExactly("event-1");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static final class AdjustableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static class MemoryStore implements OutboxDeliveryStore {
        private String status = "PENDING";
        private int totalAttempts;
        private int cycleAttempts;
        private final List<String> attemptedEventIds = new ArrayList<>();
        private Instant nextAttemptAt = Instant.parse("2026-01-01T00:00:00Z");

        @Override public int blockExpiredAttempts(Instant now) { return 0; }

        @Override
        public List<String> readyEventIds(Instant now, int limit) {
            return "PENDING".equals(status) && nextAttemptAt != null && !nextAttemptAt.isAfter(now)
                    ? List.of("event-1") : List.of();
        }

        @Override
        public Optional<DeliveryEvent> beginAttempt(String eventId, Instant now, Instant retryAt) {
            if (readyEventIds(now, 1).isEmpty()) {
                return Optional.empty();
            }
            totalAttempts++;
            cycleAttempts++;
            attemptedEventIds.add(eventId);
            nextAttemptAt = retryAt;
            return Optional.of(new DeliveryEvent(eventId, "order-events", "42", "original", cycleAttempts));
        }

        @Override public void markPublished(String eventId, Instant now) { status = "PUBLISHED"; }
        @Override public void recordFailure(String eventId, Instant next, String error) {
            nextAttemptAt = next;
            if (next == null) {
                status = "BLOCKED";
            }
        }
        @Override public boolean resume(String eventId, Instant now) {
            if (!"BLOCKED".equals(status) || !"event-1".equals(eventId)) {
                return false;
            }
            cycleAttempts = 0;
            nextAttemptAt = now;
            status = "PENDING";
            return true;
        }
    }
}

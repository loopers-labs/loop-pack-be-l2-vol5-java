package com.loopers.application.outbox;

import com.loopers.domain.outbox.OutboxDeliveryAttempt;
import com.loopers.domain.outbox.OutboxMessage;
import com.loopers.domain.outbox.OutboxReceipt;
import com.loopers.domain.outbox.OutboxRepository;
import com.loopers.domain.outbox.OutboxSender;
import com.loopers.domain.outbox.OutboxStatus;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;

/** Real isolated MySQL state transactions; mocked acknowledgements do not replace the actual broker gap experiments. */
@SpringBootTest(properties = {"commerce.outbox-relay.enabled=false", "commerce.order-events.enabled=false"})
@Timeout(30)
class OutboxRelayIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-04T03:04:05.123456Z");
    private static final String TOPIC = "owned-relay-state-test";
    private static final String PAYLOAD = "{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,"
        + "\"eventId\":\"order-confirmed:v1:42\",\"orderId\":42,"
        + "\"confirmedAt\":\"2026-11-04T03:00:00Z\",\"items\":[{\"productId\":7,\"quantity\":2}]}";

    @Autowired private OutboxDeliveryTransactions transactions;
    @Autowired private OutboxRepository outbox;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    @MockitoBean private OutboxSender sender;

    private final MutableClock clock = new MutableClock(NOW);

    @AfterEach
    void cleanOnlyOwnedTestDatabase() {
        cleanup.truncateAllTables();
    }

    @Test
    void sendRunsOutsideATransactionAndItsCommittedAttemptStaysPendingUntilAcknowledged() throws Exception {
        OutboxMessage saved = pending("order-confirmed:v1:42");
        Map<String, Object> immutableFact = facts(saved.getId());
        CountDownLatch enteredSend = new CountDownLatch(1);
        CompletableFuture<OutboxReceipt> acknowledgement = new CompletableFuture<>();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            OutboxDeliveryAttempt attempt = invocation.getArgument(0);
            assertThat(attempt.id()).isEqualTo(saved.getId());
            assertThat(attempt.eventId()).isEqualTo(saved.getEventId());
            assertThat(attempt.topic()).isEqualTo(TOPIC);
            assertThat(attempt.messageKey()).isEqualTo("42");
            assertThat(attempt.payload()).isEqualTo(PAYLOAD);
            // JDBC reads committed state outside the write transaction and cannot reuse a JPA first-level cache.
            assertThat(row(saved.getId()).get("status")).isEqualTo("PENDING");
            assertThat(row(saved.getId()).get("attempt_count")).isEqualTo(1);
            assertThat(row(saved.getId()).get("published_at")).isNull();
            enteredSend.countDown();
            return acknowledgement;
        }).when(sender).send(any(OutboxDeliveryAttempt.class));
        var worker = Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(() -> relay(Duration.ofSeconds(5)).runOnce());
            assertThat(enteredSend.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(result.isDone()).isFalse();
            assertThat(row(saved.getId()).get("status")).isEqualTo("PENDING");

            acknowledgement.complete(receipt());

            assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(new OutboxRelay.Result(1, 1, false));
            assertThat(loaded(saved).getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            assertThat(loaded(saved).getPublishedAt()).isEqualTo(NOW);
            assertThat(facts(saved.getId())).isEqualTo(immutableFact);
        } finally {
            acknowledgement.completeExceptionally(new IllegalStateException("test cleanup"));
            worker.shutdownNow();
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void timeoutKeepsTheSameFactForANewRelayAndLateOldSuccessCannotChangeTheRecoveredRow() {
        OutboxMessage saved = pending("order-confirmed:v1:42");
        Map<String, Object> immutableFact = facts(saved.getId());
        CompletableFuture<OutboxReceipt> late = new CompletableFuture<>();
        List<OutboxDeliveryAttempt> sent = new ArrayList<>();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            sent.add(invocation.getArgument(0));
            return sent.size() == 1 ? late : CompletableFuture.completedFuture(receipt());
        }).when(sender).send(any(OutboxDeliveryAttempt.class));

        assertThat(relay(Duration.ofMillis(50)).runOnce()).isEqualTo(new OutboxRelay.Result(1, 0, false));

        OutboxMessage waiting = loaded(saved);
        assertThat(waiting.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(waiting.getPublishedAt()).isNull();
        assertThat(waiting.getLastError()).isEqualTo("TIMEOUT");
        assertThat(waiting.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(facts(saved.getId())).isEqualTo(immutableFact);
        assertThat(relay(Duration.ofMillis(50)).runOnce()).isEqualTo(new OutboxRelay.Result(0, 0, false));
        assertThat(sent).hasSize(1);

        clock.moveTo(waiting.getNextAttemptAt());
        assertThat(relay(Duration.ofMillis(50)).runOnce()).isEqualTo(new OutboxRelay.Result(1, 1, false));
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).id()).isEqualTo(sent.get(0).id());
        assertThat(sent.get(1).eventId()).isEqualTo(sent.get(0).eventId());
        assertThat(sent.get(1).topic()).isEqualTo(sent.get(0).topic());
        assertThat(sent.get(1).messageKey()).isEqualTo(sent.get(0).messageKey());
        assertThat(sent.get(1).payload()).isEqualTo(sent.get(0).payload());
        assertThat(sent.get(1).attemptCount()).isEqualTo(2);
        Map<String, Object> recovered = row(saved.getId());

        assertThat(late.complete(receipt())).isTrue();

        assertThat(row(saved.getId())).isEqualTo(recovered);
        assertThat(loaded(saved).getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(facts(saved.getId())).isEqualTo(immutableFact);
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void actualPublishedUpdateFailureLeavesPendingAndANewRelayRetriesTheSamePersistedFact() {
        OutboxMessage saved = pending("order-confirmed:v1:42");
        Map<String, Object> immutableFact = facts(saved.getId());
        List<OutboxDeliveryAttempt> sent = new ArrayList<>();
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return CompletableFuture.completedFuture(receipt());
        }).when(sender).send(any(OutboxDeliveryAttempt.class));
        // A real SQL rejection in this test's disposable database; no fake PENDING reset after the failure.
        jdbc.execute("ALTER TABLE outbox_message ADD CONSTRAINT w7_relay_reject_publish CHECK (status <> 'PUBLISHED')");
        try {
            assertThat(relay(Duration.ofSeconds(12)).runOnce()).isEqualTo(new OutboxRelay.Result(1, 0, false));
            OutboxMessage waiting = loaded(saved);
            assertThat(waiting.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(waiting.getPublishedAt()).isNull();
            assertThat(waiting.getAttemptCount()).isEqualTo(1);
            assertThat(waiting.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(17));
            assertThat(facts(saved.getId())).isEqualTo(immutableFact);
            assertThat(sent).hasSize(1);
        } finally {
            jdbc.execute("ALTER TABLE outbox_message DROP CHECK w7_relay_reject_publish");
        }

        clock.moveTo(loaded(saved).getNextAttemptAt());
        assertThat(relay(Duration.ofSeconds(12)).runOnce()).isEqualTo(new OutboxRelay.Result(1, 1, false));
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).id()).isEqualTo(sent.get(0).id());
        assertThat(sent.get(1).eventId()).isEqualTo(sent.get(0).eventId());
        assertThat(sent.get(1).payload()).isEqualTo(sent.get(0).payload());
        assertThat(loaded(saved).getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(loaded(saved).getAttemptCount()).isEqualTo(2);
        assertThat(facts(saved.getId())).isEqualTo(immutableFact);
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void oldAttemptResultsCannotOverwriteTheNewAttemptOrItsPublishedState() {
        OutboxMessage saved = pending("order-confirmed:v1:42");
        var first = transactions.beginAttempt(saved.getId(), NOW, Duration.ofSeconds(12)).orElseThrow();
        clock.moveTo(loaded(saved).getNextAttemptAt());
        var second = transactions.beginAttempt(saved.getId(), clock.instant(), Duration.ofSeconds(12)).orElseThrow();
        Map<String, Object> inProgress = row(saved.getId());

        assertThat(transactions.markPublished(saved.getId(), first.attemptCount(), clock.instant())).isFalse();
        assertThat(transactions.markFailed(saved.getId(), first.attemptCount(), clock.instant(), "SEND_FAILED")).isFalse();
        assertThat(row(saved.getId())).isEqualTo(inProgress);

        assertThat(transactions.markPublished(saved.getId(), second.attemptCount(), clock.instant())).isTrue();
        Map<String, Object> published = row(saved.getId());
        assertThat(transactions.markFailed(saved.getId(), first.attemptCount(), clock.instant(), "SEND_FAILED")).isFalse();
        assertThat(transactions.markFailed(saved.getId(), second.attemptCount(), clock.instant(), "SEND_FAILED")).isFalse();
        assertThat(row(saved.getId())).isEqualTo(published);
        verifyNoInteractions(sender);
    }

    @Test
    void firstFailureDefersThatRowAndLeavesOtherDueRowsForTheNextRound() {
        OutboxMessage first = pending("first-event");
        OutboxMessage second = pending("second-event");
        List<Long> sent = new ArrayList<>();
        doAnswer(invocation -> {
            OutboxDeliveryAttempt attempt = invocation.getArgument(0);
            sent.add(attempt.id());
            if (attempt.id() == first.getId()) {
                return CompletableFuture.failedFuture(new IllegalStateException("synthetic transport failure"));
            }
            return CompletableFuture.completedFuture(receipt());
        }).when(sender).send(any(OutboxDeliveryAttempt.class));

        OutboxRelay relay = relay(Duration.ofSeconds(12));
        assertThat(relay.runOnce()).isEqualTo(new OutboxRelay.Result(1, 0, false));
        assertThat(sent).containsExactly(first.getId());
        assertThat(loaded(second).getAttemptCount()).isZero();
        assertThat(loaded(first).getNextAttemptAt()).isEqualTo(NOW.plusSeconds(5));

        assertThat(relay.runOnce()).isEqualTo(new OutboxRelay.Result(1, 1, false));
        assertThat(sent).containsExactly(first.getId(), second.getId());
        assertThat(loaded(first).getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(loaded(second).getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    void anActiveCallerTransactionCannotEncloseSendingOrStartAnAttempt() {
        OutboxMessage saved = pending("order-confirmed:v1:42");
        Map<String, Object> before = row(saved.getId());
        OutboxRelay relay = relay(Duration.ofSeconds(12));

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
            .executeWithoutResult(status -> relay.runOnce())).isInstanceOf(IllegalStateException.class);

        assertThat(row(saved.getId())).isEqualTo(before);
        verifyNoInteractions(sender);
    }

    private OutboxRelay relay(Duration sendBudget) {
        var settings = new OutboxRelaySettings(10, Duration.ofSeconds(1), sendBudget,
            Duration.ofSeconds(5), Duration.ofSeconds(60));
        return new OutboxRelay(transactions, sender, clock, settings, System::nanoTime);
    }

    private OutboxMessage pending(String eventId) {
        String payload = PAYLOAD.replace("order-confirmed:v1:42", eventId);
        return new TransactionTemplate(transactionManager).execute(status -> outbox.save(OutboxMessage.pending(
            eventId, "Order", "42", "OrderConfirmed", 1, TOPIC, "42", payload, NOW.minusSeconds(1))));
    }

    private OutboxMessage loaded(OutboxMessage original) {
        return outbox.findByEventId(original.getEventId()).orElseThrow();
    }

    private Map<String, Object> row(Long id) {
        return jdbc.queryForMap("SELECT * FROM outbox_message WHERE id=?", id);
    }

    private Map<String, Object> facts(Long id) {
        return jdbc.queryForMap("SELECT id,event_id,aggregate_type,aggregate_id,event_type,schema_version,"
            + "topic,message_key,payload,created_at FROM outbox_message WHERE id=?", id);
    }

    private long rowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_message", Long.class);
    }

    private OutboxReceipt receipt() {
        return new OutboxReceipt(TOPIC, 0, 7L);
    }

    private static final class MutableClock extends Clock {
        private volatile Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void moveTo(Instant next) {
            current = next;
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}

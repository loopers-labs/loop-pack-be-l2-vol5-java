package com.loopers.application.outbox;

import com.loopers.domain.outbox.OutboxMessage;
import com.loopers.domain.outbox.OutboxRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Uses the standard owned MySQL Testcontainer; no transaction surrounds the entire test. */
@SpringBootTest
@Timeout(40)
class OutboxDeliveryTransactionsIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00.123456Z");
    private static final Duration BUDGET = Duration.ofSeconds(12);

    @Autowired private OutboxDeliveryTransactions transactions;
    @Autowired private OutboxRepository outbox;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private PlatformTransactionManager manager;

    @AfterEach
    void cleanOwnedData() {
        cleanup.truncateAllTables();
    }

    @Test
    void onlyDuePendingIdsAreReadWithStableOrderingAndTheRequestedLimit() {
        long later = save("later", NOW).getId();
        long earlierFirst = save("earlier-first", NOW.minusSeconds(1)).getId();
        long earlierSecond = save("earlier-second", NOW.minusSeconds(1)).getId();
        save("future", NOW.plusSeconds(1));
        long published = save("published", NOW.minusSeconds(2)).getId();
        var attempt = transactions.beginAttempt(published, NOW, BUDGET).orElseThrow();
        assertThat(transactions.markPublished(published, attempt.attemptCount(), NOW)).isTrue();

        assertThat(transactions.findDueIds(NOW, 2)).containsExactly(earlierFirst, earlierSecond);
        assertThat(transactions.findDueIds(NOW, 10)).containsExactly(earlierFirst, earlierSecond, later);
        assertThatThrownBy(() -> transactions.findDueIds(NOW, 0))
            .isInstanceOf(InvalidDataAccessApiUsageException.class)
            .hasCauseInstanceOf(IllegalArgumentException.class)
            .hasRootCauseMessage("Outbox due lookup requires a time and positive limit");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void startIsCommittedBeforeDeliveryAndSurvivesASeparateOuterRollback() {
        long id = save("started", NOW).getId();
        var original = fact(id);

        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var attempt = transactions.beginAttempt(id, NOW, BUDGET).orElseThrow();
            assertThat(attempt.id()).isEqualTo(id);
            assertThat(attempt.eventId()).isEqualTo("started");
            assertThat(attempt.topic()).isEqualTo("saved-topic");
            assertThat(attempt.messageKey()).isEqualTo("42");
            assertThat(attempt.payload()).isEqualTo("{ \"orderId\":42 }");
            assertThat(attempt.attemptCount()).isEqualTo(1);
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT attempt_count FROM outbox_message WHERE id=?", Integer.class, id)).isEqualTo(1);
        assertThat(status(id)).isEqualTo("PENDING");
        assertThat(outbox.findByEventId("started").orElseThrow().getNextAttemptAt()).isEqualTo(NOW.plusSeconds(17));
        assertThat(transactions.beginAttempt(id, NOW.plusSeconds(16), BUDGET)).isEmpty();
        assertThat(transactions.beginAttempt(id, NOW.plusSeconds(17), BUDGET).orElseThrow().attemptCount()).isEqualTo(2);
        assertThat(fact(id)).isEqualTo(original);
    }

    @Test
    void failureAndSuccessUseOnlyTheCurrentAttemptAndNeverReopenPublishedRows() {
        long id = save("retry", NOW).getId();
        var original = fact(id);
        var first = transactions.beginAttempt(id, NOW, BUDGET).orElseThrow();
        assertThat(transactions.markFailed(id, first.attemptCount(), NOW.plusSeconds(1), "TIMEOUT")).isTrue();
        assertThat(outbox.findByEventId("retry").orElseThrow().getNextAttemptAt()).isEqualTo(NOW.plusSeconds(6));
        var second = transactions.beginAttempt(id, NOW.plusSeconds(6), BUDGET).orElseThrow();
        assertThat(second.attemptCount()).isEqualTo(2);
        assertThat(transactions.markPublished(id, first.attemptCount(), NOW.plusSeconds(7))).isFalse();
        assertThat(transactions.markFailed(id, first.attemptCount(), NOW.plusSeconds(7), "SEND_FAILED")).isFalse();
        assertThat(status(id)).isEqualTo("PENDING");
        assertThat(transactions.markPublished(id, second.attemptCount(), NOW.plusSeconds(7))).isTrue();
        var published = jdbc.queryForMap("SELECT * FROM outbox_message WHERE id=?", id);
        assertThat(transactions.markFailed(id, second.attemptCount(), NOW.plusSeconds(99), "TIMEOUT")).isFalse();
        assertThat(transactions.markPublished(id, second.attemptCount(), NOW.plusSeconds(99))).isFalse();
        assertThat(jdbc.queryForMap("SELECT * FROM outbox_message WHERE id=?", id)).isEqualTo(published);
        assertThat(fact(id)).isEqualTo(original);
    }

    @Test
    void actualStateUpdateFailureKeepsTheCommittedAttemptPendingForRecovery() {
        long id = save("update-failure", NOW).getId();
        var attempt = transactions.beginAttempt(id, NOW, BUDGET).orElseThrow();
        var before = jdbc.queryForMap("SELECT * FROM outbox_message WHERE id=?", id);
        jdbc.execute("ALTER TABLE outbox_message ADD CONSTRAINT w7_relay_reject_publish CHECK (status <> 'PUBLISHED')");
        try {
            assertThatThrownBy(() -> transactions.markPublished(id, attempt.attemptCount(), NOW.plusSeconds(1)))
                .isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForMap("SELECT * FROM outbox_message WHERE id=?", id)).isEqualTo(before);
        } finally {
            jdbc.execute("ALTER TABLE outbox_message DROP CHECK w7_relay_reject_publish");
        }
        assertThat(transactions.beginAttempt(id, NOW.plusSeconds(17), BUDGET).orElseThrow().attemptCount()).isEqualTo(2);
        assertThat(status(id)).isEqualTo("PENDING");
    }

    @Test
    void concurrentStartCallsSerializeOnlyTheOutboxRowAndDoNotBothStartOneDueAttempt() throws Exception {
        long id = save("concurrent", NOW).getId();
        var workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = workers.submit(() -> beginConcurrently(id, ready, start));
            var second = workers.submit(() -> beginConcurrently(id, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
            assertThat(outbox.findByEventId("concurrent").orElseThrow().getAttemptCount()).isEqualTo(1);
            assertThat(transactions.beginAttempt(Long.MAX_VALUE, NOW, BUDGET)).isEmpty();
            assertThat(transactions.markPublished(Long.MAX_VALUE, 1, NOW)).isFalse();
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private boolean beginConcurrently(long id, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return transactions.beginAttempt(id, NOW, BUDGET).isPresent();
    }

    private OutboxMessage save(String eventId, Instant createdAt) {
        return outbox.save(OutboxMessage.pending(eventId, "Order", "42", "OrderConfirmed", 1,
            "saved-topic", "42", "{ \"orderId\":42 }", createdAt));
    }

    private Object fact(long id) {
        return jdbc.queryForMap("SELECT event_id,aggregate_type,aggregate_id,event_type,schema_version,topic,"
            + "message_key,payload,created_at FROM outbox_message WHERE id=?", id);
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM outbox_message WHERE id=?", String.class, id);
    }
}

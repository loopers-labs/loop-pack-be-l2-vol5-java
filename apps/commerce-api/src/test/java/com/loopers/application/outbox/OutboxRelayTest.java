package com.loopers.application.outbox;

import com.loopers.domain.outbox.OutboxDeliveryAttempt;
import com.loopers.domain.outbox.OutboxReceipt;
import com.loopers.domain.outbox.OutboxSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Timeout(10)
class OutboxRelayTest {
    private final Instant now = Instant.parse("2026-11-04T01:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final OutboxDeliveryTransactions transactions = mock(OutboxDeliveryTransactions.class);
    private final OutboxSender sender = mock(OutboxSender.class);
    private final AtomicLong elapsed = new AtomicLong();
    private final OutboxRelaySettings settings = new OutboxRelaySettings(10, Duration.ofSeconds(1),
        Duration.ofMillis(50), Duration.ofSeconds(5), Duration.ofSeconds(60));
    private final OutboxDeliveryAttempt first = new OutboxDeliveryAttempt(1, "order-confirmed:v1:42", "order-events", "42", "{\"orderId\":42}", 1);
    private final OutboxDeliveryAttempt second = new OutboxDeliveryAttempt(2, "order-confirmed:v1:43", "order-events", "43", "{\"orderId\":43}", 1);

    @Test
    void sendsSequentiallyAndMarksPublishedOnlyAfterAcknowledgement() {
        prepare(first, second);
        when(sender.send(first)).thenReturn(CompletableFuture.completedFuture(receipt(7)));
        when(sender.send(second)).thenAnswer(invocation -> {
            verify(transactions).markPublished(1, 1, now);
            return CompletableFuture.completedFuture(receipt(8));
        });

        assertThat(relay().runOnce()).isEqualTo(new OutboxRelay.Result(2, 2, false));

        verify(transactions).findDueIds(now, 10);
        verify(transactions).markPublished(2, 1, now);
        verify(transactions, never()).markFailed(anyLong(), anyInt(), any(), any());
    }

    @Test
    void anUncompletedFutureCannotPublishAndAnOverlappingRunIsSkipped() throws Exception {
        prepare(first);
        when(transactions.beginAttempt(first.id(), now, Duration.ofSeconds(2))).thenReturn(Optional.of(first));
        var future = new CompletableFuture<OutboxReceipt>();
        var entered = new CountDownLatch(1);
        when(sender.send(first)).thenAnswer(invocation -> { entered.countDown(); return future; });
        var runner = new OutboxRelay(transactions, sender, clock, new OutboxRelaySettings(10, Duration.ofSeconds(1),
            Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(60)), System::nanoTime);
        var workers = Executors.newSingleThreadExecutor();
        try {
            var running = workers.submit(runner::runOnce);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            verify(transactions, never()).markPublished(anyLong(), anyInt(), any());
            assertThat(runner.runOnce()).isEqualTo(new OutboxRelay.Result(0, 0, true));
            future.complete(receipt(7));
            assertThat(running.get(2, TimeUnit.SECONDS)).isEqualTo(new OutboxRelay.Result(1, 1, false));
            verify(transactions, times(1)).findDueIds(now, 10);
        } finally {
            future.complete(receipt(7));
            workers.shutdownNow();
            assertThat(workers.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void sendAdmissionTimeConsumesTheSameBudgetAndLateSuccessCannotUpdateState() {
        prepare(first, second);
        var late = new CompletableFuture<OutboxReceipt>();
        when(sender.send(first)).thenAnswer(invocation -> {
            elapsed.set(settings.sendBudget().toNanos() + 1);
            return late;
        });

        assertThat(relay().runOnce()).isEqualTo(new OutboxRelay.Result(1, 0, false));
        late.complete(receipt(7));

        verify(transactions).markFailed(1, 1, now, "TIMEOUT");
        verify(transactions, never()).markPublished(anyLong(), anyInt(), any());
        verify(sender, never()).send(second);
    }

    @Test
    void asynchronousFailureDefersTheSameAttemptAndEndsThisRound() {
        prepare(first, second);
        when(sender.send(first)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("private-payload")));

        assertThat(relay().runOnce()).isEqualTo(new OutboxRelay.Result(1, 0, false));

        verify(transactions).markFailed(1, 1, now, "SEND_FAILED");
        verify(sender, never()).send(second);
        verify(transactions, never()).beginAttempt(eq(2L), any(), any());
    }

    @Test
    void synchronousSendFailureAlsoDefersTheSameAttempt() {
        prepare(first);
        when(sender.send(first)).thenThrow(new IllegalArgumentException("private-payload"));
        assertThat(relay().runOnce()).isEqualTo(new OutboxRelay.Result(1, 0, false));
        verify(transactions).markFailed(1, 1, now, "SEND_FAILED");
    }

    @Test
    void acknowledgedButFailedStateWriteNeverBlindlyResetsPublishedState() {
        prepare(first, second);
        when(sender.send(first)).thenReturn(CompletableFuture.completedFuture(receipt(7)));
        doThrow(new IllegalStateException("commit result unavailable")).when(transactions).markPublished(1, 1, now);

        assertThat(relay().runOnce()).isEqualTo(new OutboxRelay.Result(1, 0, false));

        verify(transactions, never()).markFailed(anyLong(), anyInt(), any(), any());
        verify(sender, never()).send(second);
    }

    @Test
    void aFailureToSaveTheRetryDoesNotStartAnotherSend() {
        prepare(first, second);
        when(sender.send(first)).thenThrow(new IllegalStateException("send failed"));
        doThrow(new IllegalStateException("DB down")).when(transactions).markFailed(1, 1, now, "SEND_FAILED");

        assertThat(relay().runOnce()).isEqualTo(new OutboxRelay.Result(1, 0, false));
        verify(sender, never()).send(second);
    }

    @Test
    void aReceiptForAnotherTopicIsNotPublicationProof() {
        prepare(first);
        when(sender.send(first)).thenReturn(CompletableFuture.completedFuture(new OutboxReceipt("wrong-topic", 0, 7)));

        assertThat(relay().runOnce()).isEqualTo(new OutboxRelay.Result(1, 0, false));
        verify(transactions).markFailed(1, 1, now, "SEND_FAILED");
        verify(transactions, never()).markPublished(anyLong(), anyInt(), any());
    }

    @Test
    void aCandidateWhichIsNoLongerDueDoesNotSend() {
        when(transactions.findDueIds(now, 10)).thenReturn(List.of(1L));
        when(transactions.beginAttempt(1, now, settings.sendBudget())).thenReturn(Optional.empty());

        assertThat(relay().runOnce()).isEqualTo(new OutboxRelay.Result(0, 0, false));
        verifyNoInteractions(sender);
    }

    @Test
    void lookupFailureReleasesTheSingleRunnerGuard() {
        when(transactions.findDueIds(now, 10)).thenThrow(new IllegalStateException("DB down")).thenReturn(List.of());
        var relay = relay();
        assertThat(relay.runOnce()).isEqualTo(new OutboxRelay.Result(0, 0, false));
        assertThat(relay.runOnce()).isEqualTo(new OutboxRelay.Result(0, 0, false));
        verifyNoInteractions(sender);
    }

    @Test
    void rejectsAnExistingCallerTransactionBeforeDoingAnything() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> relay().runOnce()).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(transactions, sender);
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    private void prepare(OutboxDeliveryAttempt... attempts) {
        when(transactions.findDueIds(now, 10)).thenReturn(java.util.Arrays.stream(attempts).map(OutboxDeliveryAttempt::id).toList());
        for (var attempt : attempts) {
            when(transactions.beginAttempt(attempt.id(), now, settings.sendBudget())).thenReturn(Optional.of(attempt));
            when(transactions.markPublished(attempt.id(), attempt.attemptCount(), now)).thenReturn(true);
        }
    }

    private OutboxReceipt receipt(long offset) { return new OutboxReceipt("order-events", 0, offset); }
    private OutboxRelay relay() { return new OutboxRelay(transactions, sender, clock, settings, elapsed::get); }
}

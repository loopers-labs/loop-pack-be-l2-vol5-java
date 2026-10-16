package com.loopers.application.product;

import com.loopers.domain.common.PageResult;
import com.loopers.domain.product.ProductListSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductListSingleFlightTest {
    private static final long WAIT_MILLIS = 5000;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @AfterEach
    void stopWorkers() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void sharesOneResultWithConcurrentSameVersionCalls() throws Exception {
        ProductListSingleFlight flights = new ProductListSingleFlight(WAIT_MILLIS, 64);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        ProductListSnapshot expected = snapshot(7);
        Worker<Optional<ProductListSnapshot>> leader = submit(() -> flights.load(7, () -> {
            loads.incrementAndGet();
            entered.countDown();
            await(release);
            return expected;
        }));
        await(entered);
        List<Worker<Optional<ProductListSnapshot>>> followers = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            Worker<Optional<ProductListSnapshot>> follower = submit(() -> flights.load(7, () -> {
                loads.incrementAndGet();
                return snapshot(7);
            }));
            awaitWaiting(follower);
            followers.add(follower);
        }

        release.countDown();

        assertThat(result(leader)).containsSame(expected);
        for (Worker<Optional<ProductListSnapshot>> follower : followers) {
            assertThat(result(follower)).containsSame(expected);
        }
        assertThat(loads).hasValue(1);
    }

    @Test
    void differentVersionsCanLoadIndependently() throws Exception {
        ProductListSingleFlight flights = new ProductListSingleFlight(WAIT_MILLIS, 2);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Worker<Optional<ProductListSnapshot>> first = submit(() -> flights.load(7, blocked(7, entered, release)));
        Worker<Optional<ProductListSnapshot>> second = submit(() -> flights.load(8, blocked(8, entered, release)));
        await(entered);

        release.countDown();

        assertThat(result(first)).contains(snapshot(7));
        assertThat(result(second)).contains(snapshot(8));
    }

    @Test
    void preservesTheSnapshotRevisionRatherThanRelabellingItWithTheLookupRevision() {
        ProductListSingleFlight flights = new ProductListSingleFlight(WAIT_MILLIS, 1);

        assertThat(flights.load(7, () -> snapshot(8))).contains(snapshot(8));
    }

    @Test
    void successfulResultsAreNotRetainedAsALocalCacheAndCapacityIsReturned() {
        ProductListSingleFlight flights = new ProductListSingleFlight(WAIT_MILLIS, 1);
        AtomicInteger loads = new AtomicInteger();

        assertThat(flights.load(7, () -> snapshot(loads.incrementAndGet()))).contains(snapshot(1));
        assertThat(flights.load(7, () -> snapshot(loads.incrementAndGet()))).contains(snapshot(2));
        assertThat(flights.load(8, () -> snapshot(loads.incrementAndGet()))).contains(snapshot(3));
        assertThat(loads).hasValue(3);
    }

    @Test
    void databaseFailureIsSharedWithoutWrappingAndTheSameVersionCanRetry() throws Exception {
        verifyFailureAndRetry(new IllegalStateException("database unavailable"));
    }

    @Test
    void errorsAreSharedAndDoNotLeaveAStuckFlightOrCapacityPermit() throws Exception {
        verifyFailureAndRetry(new AssertionError("loader error"));
    }

    @Test
    void aLoaderCompletionExceptionIsNotUnwrappedDifferentlyForFollowers() throws Exception {
        verifyFailureAndRetry(new CompletionException(new IllegalStateException("nested failure")));
    }

    @Test
    void timeoutDoesNotCancelTheLeaderOrRemoveTheFlightForOtherFollowers() throws Exception {
        ProductListSingleFlight flights = new ProductListSingleFlight(500, 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Worker<Optional<ProductListSnapshot>> leader = submit(() -> flights.load(7, blocked(7, entered, release)));
        await(entered);

        Worker<Optional<ProductListSnapshot>> impatient = submit(() -> flights.load(7, unexpectedLoader()));
        assertThat(result(impatient)).isEmpty();
        assertThat(leader.future().isDone()).isFalse();
        assertThat(flights.load(8, unexpectedLoader())).isEmpty();
        Worker<Optional<ProductListSnapshot>> nextFollower = submit(() -> flights.load(7, unexpectedLoader()));
        awaitWaiting(nextFollower);
        release.countDown();

        assertThat(result(leader)).contains(snapshot(7));
        assertThat(result(nextFollower)).contains(snapshot(7));
        assertThat(flights.load(8, () -> snapshot(8))).contains(snapshot(8));
    }

    @Test
    void interruptionRestoresTheFlagWithoutCancellingOtherParticipants() throws Exception {
        ProductListSingleFlight flights = new ProductListSingleFlight(WAIT_MILLIS, 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        Worker<Optional<ProductListSnapshot>> leader = submit(() -> flights.load(7, blocked(7, entered, release)));
        await(entered);
        Worker<Optional<ProductListSnapshot>> cancelled = submit(() -> {
            try {
                return flights.load(7, unexpectedLoader());
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        Worker<Optional<ProductListSnapshot>> other = submit(() -> flights.load(7, unexpectedLoader()));
        awaitWaiting(cancelled);
        awaitWaiting(other);

        cancelled.thread().get().interrupt();

        Throwable failure = assertThrows(ExecutionException.class, () -> result(cancelled)).getCause();
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(InterruptedException.class);
        assertThat(interrupted).isTrue();
        assertThat(leader.future().isDone()).isFalse();
        assertThat(other.future().isDone()).isFalse();
        release.countDown();
        assertThat(result(leader)).contains(snapshot(7));
        assertThat(result(other)).contains(snapshot(7));
    }

    @Test
    void fullCapacityRejectsANewVersionButAllowsJoiningAnExistingVersionAndRecovers() throws Exception {
        ProductListSingleFlight flights = new ProductListSingleFlight(WAIT_MILLIS, 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Worker<Optional<ProductListSnapshot>> leader = submit(() -> flights.load(7, blocked(7, entered, release)));
        await(entered);

        assertThat(flights.load(8, unexpectedLoader())).isEmpty();
        Worker<Optional<ProductListSnapshot>> follower = submit(() -> flights.load(7, unexpectedLoader()));
        awaitWaiting(follower);
        release.countDown();

        assertThat(result(leader)).contains(snapshot(7));
        assertThat(result(follower)).contains(snapshot(7));
        assertThat(flights.load(8, () -> snapshot(8))).contains(snapshot(8));
    }

    @Test
    void nullLoaderResultsFailAndReleaseTheSlotForRetry() {
        ProductListSingleFlight flights = new ProductListSingleFlight(WAIT_MILLIS, 1);

        assertThatThrownBy(() -> flights.load(7, () -> null)).isInstanceOf(NullPointerException.class);
        assertThat(flights.load(7, () -> snapshot(7))).contains(snapshot(7));
    }

    @Test
    void rejectsNonPositiveWaitAndCapacitySettings() {
        for (long waitMillis : List.of(0L, -1L)) {
            assertThatThrownBy(() -> new ProductListSingleFlight(waitMillis, 1))
                .isInstanceOf(IllegalArgumentException.class);
        }
        for (int capacity : List.of(0, -1)) {
            assertThatThrownBy(() -> new ProductListSingleFlight(500, capacity))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void exposesTheEnabledSettingWithoutChangingTheLoaderContract() {
        assertThat(new ProductListSingleFlight(500, 64).isEnabled()).isTrue();
        assertThat(new ProductListSingleFlight(500, 64, false).isEnabled()).isFalse();
    }

    private void verifyFailureAndRetry(Throwable failure) throws Exception {
        ProductListSingleFlight flights = new ProductListSingleFlight(WAIT_MILLIS, 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Worker<Optional<ProductListSnapshot>> leader = submit(() -> flights.load(7, () -> {
            entered.countDown();
            await(release);
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw (Error) failure;
        }));
        await(entered);
        Worker<Optional<ProductListSnapshot>> follower = submit(() -> flights.load(7, unexpectedLoader()));
        awaitWaiting(follower);
        release.countDown();

        assertThat(assertThrows(ExecutionException.class, () -> result(leader)).getCause()).isSameAs(failure);
        assertThat(assertThrows(ExecutionException.class, () -> result(follower)).getCause()).isSameAs(failure);
        assertThat(flights.load(7, () -> snapshot(7))).contains(snapshot(7));
        assertThat(flights.load(8, () -> snapshot(8))).contains(snapshot(8));
    }

    private Supplier<ProductListSnapshot> blocked(long revision, CountDownLatch entered, CountDownLatch release) {
        return () -> {
            entered.countDown();
            await(release);
            return snapshot(revision);
        };
    }

    private Supplier<ProductListSnapshot> unexpectedLoader() {
        return () -> {
            throw new AssertionError("A follower or capacity-rejected request must not invoke its loader");
        };
    }

    private ProductListSnapshot snapshot(long revision) {
        return new ProductListSnapshot(revision, PageResult.of(List.of(), 0, 20, 0));
    }

    private <T> Worker<T> submit(Callable<T> task) throws InterruptedException {
        AtomicReference<Thread> thread = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        Future<T> future = executor.submit(() -> {
            thread.set(Thread.currentThread());
            started.countDown();
            return task.call();
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        return new Worker<>(future, thread);
    }

    private <T> T result(Worker<T> worker) throws Exception {
        return worker.future().get(5, TimeUnit.SECONDS);
    }

    private void awaitWaiting(Worker<?> worker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!worker.future().isDone() && System.nanoTime() < deadline) {
            if (worker.thread().get().getState() == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.yield();
        }
        throw new AssertionError("Follower did not enter bounded waiting before completion or deadline");
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test worker interrupted", exception);
        }
    }

    private record Worker<T>(Future<T> future, AtomicReference<Thread> thread) {
    }
}

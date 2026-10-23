package com.loopers.application.order;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentConfirmationWaiterTest {
    private final FakeTiming timing = new FakeTiming();
    private final PaymentConfirmationWaiter waiter = new PaymentConfirmationWaiter(timing);
    private final PaymentConfirmationDeadline deadline = PaymentConfirmationDeadline.start(timing);
    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> stored = new AtomicReference<>("PENDING");

    @AfterEach
    void clearsThreadState() {
        TransactionSynchronizationManager.clear();
        Thread.interrupted();
    }

    @Test
    void firstLookupIsImmediateAndFinishesAsSoonAsTheStoredResultIsTerminal() {
        String result = waiter.await(deadline, this::read, this::terminal, () -> {
            calls.incrementAndGet();
            assertThat(timing.nanoTime()).isZero();
            stored.set("SUCCESS");
            return new PaymentConfirmationWaiter.LookupAttempt<>("SUCCESS", true);
        });

        assertThat(result).isEqualTo("SUCCESS");
        assertThat(calls).hasValue(1);
        assertThat(reads.get()).isGreaterThanOrEqualTo(2);
        assertThat(timing.sleeps).isEmpty();
    }

    @Test
    void twelvePermittedCallsIncludeTheFirstAndUseFiveTenThenFifteenSecondIntervals() {
        String result = waiter.await(deadline, this::read, this::terminal, this::pending);

        assertThat(result).isEqualTo("PENDING");
        assertThat(calls).hasValue(12);
        assertThat(timing.sleeps).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(10),
            Duration.ofSeconds(15), Duration.ofSeconds(15), Duration.ofSeconds(15), Duration.ofSeconds(15),
            Duration.ofSeconds(15), Duration.ofSeconds(15), Duration.ofSeconds(15), Duration.ofSeconds(15),
            Duration.ofSeconds(15));
        assertThat(timing.nanoTime()).isEqualTo(Duration.ofSeconds(150).toNanos());
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.8, 1.0, 1.2})
    void jitterStaysWithinTheSelectedTwentyPercentRange(double factor) {
        timing.factor = factor;
        waiter.await(deadline, this::read, this::terminal, () -> {
            var result = pending();
            if (calls.get() == 4) {
                stored.set("SUCCESS");
            }
            return result;
        });

        assertThat(timing.sleeps).containsExactly(Duration.ofNanos((long) (5_000_000_000L * factor)),
            Duration.ofNanos((long) (10_000_000_000L * factor)),
            Duration.ofNanos((long) (15_000_000_000L * factor)));
    }

    @Test
    void timeSpentBeforeWaitingUsesTheOriginalEntryDeadlineRatherThanANewOne() {
        timing.advance(Duration.ofSeconds(160));
        waiter.await(deadline, this::read, this::terminal, () -> {
            timing.advance(Duration.ofSeconds(10));
            return pending();
        });

        assertThat(calls).hasValue(1);
        assertThat(timing.sleeps).isEmpty();
        assertThat(timing.nanoTime()).isEqualTo(Duration.ofSeconds(170).toNanos());
    }

    @Test
    void permitsLookupAtExactTenSecondRemainderButNotOneNanosecondLater() {
        timing.advance(Duration.ofSeconds(165));
        assertThat(deadline.permitsLookup()).isTrue();
        timing.advance(Duration.ofNanos(1));
        assertThat(deadline.permitsLookup()).isFalse();
    }

    @Test
    void permitsCreateOnlyWhenItsEntireSixtyFiveSecondBudgetStillFits() {
        timing.advance(Duration.ofSeconds(110));
        assertThat(deadline.permitsCreate()).isTrue();
        timing.advance(Duration.ofNanos(1));
        assertThat(deadline.permitsCreate()).isFalse();
    }

    @Test
    void noLookupStartsAfterTheExternalDeadlineEvenIfTheOverallGoalHasNotElapsed() {
        timing.advance(Duration.ofSeconds(176));
        assertThat(waiter.await(deadline, this::read, this::terminal, this::pending)).isEqualTo("PENDING");
        assertThat(calls).hasValue(0);
        assertThat(timing.sleeps).isEmpty();
    }

    @Test
    void stopsInsteadOfShorteningTheSelectedIntervalToFitOneMoreLookup() {
        timing.advance(Duration.ofSeconds(162));
        waiter.await(deadline, this::read, this::terminal, this::pending);

        assertThat(calls).hasValue(1);
        assertThat(timing.sleeps).isEmpty();
        assertThat(timing.nanoTime()).isEqualTo(Duration.ofSeconds(162).toNanos());
    }

    @ParameterizedTest
    @CsvSource({"157, 2", "147, 3"})
    void laterIntervalsAlsoRequireTheirFullSelectedDelayAndTheFullHttpBudget(int enteredSeconds, int expectedCalls) {
        timing.advance(Duration.ofSeconds(enteredSeconds));
        waiter.await(deadline, this::read, this::terminal, this::pending);

        assertThat(calls).hasValue(expectedCalls);
        assertThat(timing.sleeps).hasSize(expectedCalls - 1);
        assertThat(timing.nanoTime()).isEqualTo(Duration.ofSeconds(162).toNanos());
    }

    @Test
    void circuitRejectionsDoNotConsumeTheTwelveHttpAttemptsAndCannotBusyLoop() {
        AtomicInteger rejected = new AtomicInteger();
        String result = waiter.await(deadline, this::read, this::terminal, () -> {
            if (rejected.getAndIncrement() < 3) {
                return new PaymentConfirmationWaiter.LookupAttempt<>("PENDING", false);
            }
            var attempt = pending();
            if (calls.get() == 10) {
                stored.set("SUCCESS");
            }
            return attempt;
        });

        assertThat(result).isEqualTo("SUCCESS");
        assertThat(calls).hasValue(10);
        assertThat(rejected.get()).isEqualTo(13);
        assertThat(timing.sleeps).hasSize(12);
    }

    @Test
    void alwaysOpenCircuitTerminatesWithinTheSameDeadlineWithoutHttp() {
        AtomicInteger rejected = new AtomicInteger();
        waiter.await(deadline, this::read, this::terminal, () -> {
            rejected.incrementAndGet();
            return new PaymentConfirmationWaiter.LookupAttempt<>("PENDING", false);
        });

        assertThat(calls).hasValue(0);
        assertThat(rejected.get()).isBetween(1, 20);
        assertThat(timing.nanoTime()).isLessThanOrEqualTo(Duration.ofSeconds(175).toNanos());
        assertThat(timing.sleeps).allMatch(duration -> !duration.isZero() && !duration.isNegative());
    }

    @Test
    void alreadyTerminalSnapshotDoesNotTriggerAnyLookupOrSleep() {
        stored.set("FAILED");
        assertThat(waiter.await(deadline, this::read, this::terminal, this::pending)).isEqualTo("FAILED");
        assertThat(calls).hasValue(0);
        assertThat(timing.sleeps).isEmpty();
    }

    @Test
    void callbackCompletionWhileWaitingPreventsTheNextLookup() {
        timing.onSleep = () -> stored.set("SUCCESS");
        assertThat(waiter.await(deadline, this::read, this::terminal, this::pending)).isEqualTo("SUCCESS");
        assertThat(calls).hasValue(1);
        assertThat(timing.sleeps).hasSize(1);
    }

    @Test
    void stateReadThatConsumesTheRemainingBudgetPreventsLookup() {
        timing.advance(Duration.ofSeconds(164));
        String result = waiter.await(deadline, () -> {
            timing.advance(Duration.ofSeconds(2));
            return read();
        }, this::terminal, this::pending);

        assertThat(result).isEqualTo("PENDING");
        assertThat(calls).hasValue(0);
    }

    @Test
    void finalReadPreservesTerminalStateEvenWhenTheLastLookupResultWasPending() {
        String result = waiter.await(deadline, this::read, this::terminal, () -> {
            var attempt = pending();
            if (calls.get() == 12) {
                stored.set("SUCCESS");
            }
            return attempt;
        });
        assertThat(result).isEqualTo("SUCCESS");
        assertThat(calls).hasValue(12);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1, 0, 0.79, 1.21, Double.NaN, Double.POSITIVE_INFINITY})
    void invalidJitterCannotDisableTheWaitOrExtendTheApprovedRange(double factor) {
        timing.factor = factor;
        assertThatThrownBy(() -> waiter.await(deadline, this::read, this::terminal, this::pending))
            .isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(1);
        assertThat(timing.sleeps).isEmpty();
    }

    @Test
    void internalPersistenceFailureEscapesInsteadOfBecomingAFalsePendingResult() {
        IllegalStateException failure = new IllegalStateException("injected TX B failure");
        assertThatThrownBy(() -> waiter.await(deadline, this::read, this::terminal, () -> {
            throw failure;
        })).isSameAs(failure);
        assertThat(timing.sleeps).isEmpty();
    }

    @Test
    void interruptedWaitStopsAndPreservesTheInterruptFlagAndFreshState() {
        timing.interrupt = true;
        timing.onSleep = () -> stored.set("SUCCESS");
        assertThat(waiter.await(deadline, this::read, this::terminal, this::pending)).isEqualTo("SUCCESS");
        assertThat(calls).hasValue(1);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refusesToWaitInsideAnActiveOrSynchronizationOnlyTransaction(boolean actual) {
        if (actual) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
        } else {
            TransactionSynchronizationManager.initSynchronization();
        }
        assertThatThrownBy(() -> waiter.await(deadline, this::read, this::terminal, this::pending))
            .isInstanceOf(IllegalStateException.class);
        assertThat(reads).hasValue(0);
        assertThat(calls).hasValue(0);
        assertThat(timing.sleeps).isEmpty();
    }

    @Test
    void monotonicElapsedTimeRemainsValidAcrossNanoTimeWraparound() {
        timing.now = Long.MAX_VALUE - Duration.ofSeconds(1).toNanos();
        PaymentConfirmationDeadline wrapping = PaymentConfirmationDeadline.start(timing);
        timing.advance(Duration.ofSeconds(111));
        assertThat(wrapping.permitsCreate()).isFalse();
        assertThat(wrapping.permitsLookup()).isTrue();
    }

    private String read() {
        reads.incrementAndGet();
        return stored.get();
    }

    private boolean terminal(String state) {
        return state.equals("SUCCESS") || state.equals("FAILED");
    }

    private PaymentConfirmationWaiter.LookupAttempt<String> pending() {
        calls.incrementAndGet();
        return new PaymentConfirmationWaiter.LookupAttempt<>("PENDING", true);
    }

    private static final class FakeTiming implements PaymentConfirmationTiming {
        private long now;
        private double factor = 1.0;
        private final List<Duration> sleeps = new ArrayList<>();
        private Runnable onSleep = () -> { };
        private boolean interrupt;

        @Override
        public long nanoTime() {
            return now;
        }

        @Override
        public void sleep(Duration duration) throws InterruptedException {
            sleeps.add(duration);
            onSleep.run();
            if (interrupt) {
                throw new InterruptedException("injected wait interrupt");
            }
            advance(duration);
        }

        @Override
        public double jitterFactor() {
            return factor;
        }

        private void advance(Duration duration) {
            now += duration.toNanos();
        }
    }
}

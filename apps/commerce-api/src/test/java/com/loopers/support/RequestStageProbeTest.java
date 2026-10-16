package com.loopers.support;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestStageProbeTest {

    @Test
    void recordsEntryIdentityRevisionsAndExactActionDurationWithoutChangingTheResult() throws Throwable {
        AtomicReference<String> requestId = new AtomicReference<>("read-1");
        AtomicLong clock = new AtomicLong(100);
        RequestStageProbe probe = new RequestStageProbe(requestId::get, clock::get);
        Result result = new Result(42L, "miss");
        probe.start();

        Result actual = probe.measure("cache.find", 41L, () -> {
            requestId.set("changed-after-entry");
            clock.set(180);
            return result;
        }, value -> {
            clock.set(500);
            return value.revision();
        }, Result::outcome);

        assertThat(actual).isSameAs(result);
        assertThat(probe.stop()).containsExactly(new RequestStageProbe.Event(
            "read-1", "cache.find", 41L, 42L, "miss", 100, 180, true));
    }

    @Test
    void recordsFailureAndRethrowsTheExactCheckedExceptionWithoutClassifyingAResult() {
        var times = new ArrayDeque<>(List.of(11L, 29L));
        RequestStageProbe probe = new RequestStageProbe(() -> "read-failed", times::removeFirst);
        SQLException failure = new SQLException("test-only connection failure");
        AtomicInteger classifiers = new AtomicInteger();
        probe.start();

        assertThatThrownBy(() -> probe.measure("reader.snapshot", null, () -> {
            throw failure;
        }, value -> {
            classifiers.incrementAndGet();
            return 1L;
        }, value -> {
            classifiers.incrementAndGet();
            return "ok";
        })).isSameAs(failure);

        assertThat(classifiers).hasValue(0);
        assertThat(probe.stop()).containsExactly(new RequestStageProbe.Event(
            "read-failed", "reader.snapshot", null, null, "error", 11, 29, false));
    }

    @Test
    void inactiveMeasurementsExecuteOnlyTheActionAndPreserveNullResultsAndExceptions() throws Throwable {
        AtomicInteger observations = new AtomicInteger();
        RequestStageProbe probe = new RequestStageProbe(() -> {
            observations.incrementAndGet();
            return "read-1";
        }, () -> observations.incrementAndGet());
        AtomicInteger calls = new AtomicInteger();

        Object result = probe.measure("inactive", null, () -> {
            calls.incrementAndGet();
            return null;
        }, value -> {
            observations.incrementAndGet();
            return null;
        }, value -> {
            observations.incrementAndGet();
            return "ok";
        });
        AssertionError failure = new AssertionError("same instance must escape");
        assertThatThrownBy(() -> probe.measure("inactive-failure", null, () -> {
            calls.incrementAndGet();
            throw failure;
        }, value -> null, value -> "ok")).isSameAs(failure);

        assertThat(result).isNull();
        assertThat(calls).hasValue(2);
        assertThat(observations).hasValue(0);
        assertThat(probe.stop()).isEmpty();
    }

    @Test
    void missingRequestIdentitySkipsTheClockAndClassifiersWhileTheActionStillRuns() throws Throwable {
        AtomicReference<String> requestId = new AtomicReference<>();
        AtomicInteger observations = new AtomicInteger();
        RequestStageProbe probe = new RequestStageProbe(requestId::get, () -> observations.incrementAndGet());
        probe.start();
        String result = probe.measure("without-id", null, () -> "unchanged", value -> {
            observations.incrementAndGet();
            return null;
        }, value -> {
            observations.incrementAndGet();
            return "ok";
        });

        assertThat(result).isEqualTo("unchanged");
        assertThat(observations).hasValue(0);
        assertThat(probe.stop()).isEmpty();
    }

    @Test
    void nestedDurationsIncludeInnerWorkAndAreNotExclusiveOrAdditive() throws Throwable {
        var times = new ArrayDeque<>(List.of(100L, 110L, 150L, 170L));
        RequestStageProbe probe = new RequestStageProbe(() -> "nested", times::removeFirst);
        probe.start();

        String result = probe.measure("outer", null,
            () -> probe.measure("inner", 7L, () -> "result", value -> 7L, value -> "hit"),
            value -> null, value -> "ok");

        List<RequestStageProbe.Event> events = probe.stop();
        assertThat(result).isEqualTo("result");
        assertThat(events).extracting(RequestStageProbe.Event::stage).containsExactly("inner", "outer");
        assertThat(events).extracting(RequestStageProbe.Event::elapsedNanos).containsExactly(40L, 70L);
        assertThat(events.getFirst().startNanos()).isGreaterThan(events.getLast().startNanos());
        assertThat(events.getFirst().endNanos()).isLessThan(events.getLast().endNanos());
    }

    @Test
    void concurrentActionsRemainConcurrentAndKeepTheirOwnRequestIdentity() throws Exception {
        ThreadLocal<String> requestId = new ThreadLocal<>();
        RequestStageProbe probe = new RequestStageProbe(requestId::get);
        CountDownLatch executing = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(4);
        List<Future<String>> workers = new ArrayList<>();
        probe.start();
        try {
            for (int index = 0; index < 4; index++) {
                String id = "read-" + index;
                workers.add(executor.submit(() -> {
                    requestId.set(id);
                    try {
                        return measure(probe, "concurrent", () -> {
                            executing.countDown();
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("test worker release timed out");
                            }
                            return id;
                        });
                    } finally {
                        requestId.remove();
                    }
                }));
            }
            assertThat(executing.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            for (int index = 0; index < workers.size(); index++) {
                assertThat(workers.get(index).get(5, TimeUnit.SECONDS)).isEqualTo("read-" + index);
            }
            List<RequestStageProbe.Event> events = probe.stop();
            assertThat(events).hasSize(4).extracting(RequestStageProbe.Event::requestId)
                .containsExactlyInAnyOrder("read-0", "read-1", "read-2", "read-3");
            assertThat(events).allSatisfy(event -> {
                assertThat(event.success()).isTrue();
                assertThat(event.elapsedNanos()).isGreaterThanOrEqualTo(0);
            });
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            probe.stop();
        }
    }

    @Test
    void returnedSnapshotsAreImmutableAndOverlappingSessionsAreRejected() throws Throwable {
        RequestStageProbe probe = new RequestStageProbe(() -> "read-1");
        probe.start();
        probe.measure("first", null, () -> null, value -> null, value -> "ok");
        assertThatThrownBy(probe::start).isInstanceOf(IllegalStateException.class);
        List<RequestStageProbe.Event> first = probe.stop();
        assertThatThrownBy(() -> first.add(first.getFirst())).isInstanceOf(UnsupportedOperationException.class);

        probe.start();
        assertThat(probe.stop()).isEmpty();
        assertThat(first).hasSize(1);
    }

    @Test
    void lateCompletionAfterStopIsNotRecorded() throws Exception {
        assertLateCompletionIsIgnored(false);
    }

    @Test
    void lateCompletionDoesNotPolluteTheNextSession() throws Exception {
        assertLateCompletionIsIgnored(true);
    }

    private void assertLateCompletionIsIgnored(boolean restart) throws Exception {
        RequestStageProbe probe = new RequestStageProbe(() -> "read-old");
        CountDownLatch executing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        probe.start();
        try {
            Future<String> worker = executor.submit(() -> measure(probe, "old", () -> {
                executing.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test worker release timed out");
                }
                return "old-result";
            }));
            assertThat(executing.await(5, TimeUnit.SECONDS)).isTrue();
            List<RequestStageProbe.Event> stopped = probe.stop();
            assertThat(stopped).isEmpty();
            if (restart) {
                probe.start();
                assertThat(measure(probe, "new", () -> "new-result")).isEqualTo("new-result");
            }
            release.countDown();
            assertThat(worker.get(5, TimeUnit.SECONDS)).isEqualTo("old-result");
            assertThat(stopped).isEmpty();
            if (restart) {
                assertThat(probe.stop()).extracting(RequestStageProbe.Event::stage).containsExactly("new");
            } else {
                assertThat(probe.stop()).isEmpty();
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            probe.stop();
        }
    }

    private static <T> T measure(RequestStageProbe probe, String stage, RequestStageProbe.ThrowingSupplier<T> action) {
        try {
            return probe.measure(stage, null, action, value -> null, value -> "ok");
        } catch (Throwable failure) {
            throw new AssertionError("test action failed", failure);
        }
    }

    private record Result(Long revision, String outcome) {
    }
}

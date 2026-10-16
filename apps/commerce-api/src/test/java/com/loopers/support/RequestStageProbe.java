package com.loopers.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 요청별 메서드 구간을 관찰하며 운영 코드의 실행 순서나 동시 실행을 제어하지 않는다. */
public final class RequestStageProbe {
    private final Supplier<String> requestId;
    private final LongSupplier clock;
    private final List<Event> events = new ArrayList<>();
    private boolean active;
    private long session;

    public RequestStageProbe(Supplier<String> requestId) {
        this(requestId, System::nanoTime);
    }

    public RequestStageProbe(Supplier<String> requestId, LongSupplier clock) {
        this.requestId = Objects.requireNonNull(requestId);
        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized void start() {
        if (active) {
            throw new IllegalStateException("이전 측정을 종료한 뒤 새 측정을 시작해야 합니다.");
        }
        events.clear();
        session++;
        active = true;
    }

    public synchronized List<Event> stop() {
        active = false;
        return List.copyOf(events);
    }

    public <T> T measure(String stage, Long inputRevision, ThrowingSupplier<T> action,
                         Function<T, Long> resultRevision, Function<T, String> outcome) throws Throwable {
        long observedSession = activeSession();
        String id = observedSession < 0 ? null : requestId.get();
        if (id == null) {
            return action.get();
        }
        long startedAt = clock.getAsLong();
        T result;
        try {
            result = action.get();
        } catch (Throwable failure) {
            record(observedSession, new Event(id, stage, inputRevision, null, "error",
                startedAt, clock.getAsLong(), false));
            throw failure;
        }
        long endedAt = clock.getAsLong();
        record(observedSession, new Event(id, stage, inputRevision, resultRevision.apply(result),
            outcome.apply(result), startedAt, endedAt, true));
        return result;
    }

    private synchronized long activeSession() {
        return active ? session : -1;
    }

    private synchronized void record(long observedSession, Event event) {
        if (active && session == observedSession) {
            events.add(event);
        }
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Throwable;
    }

    /**
     * start/end는 같은 JVM의 단조 시각이며 벽시계가 아니다. 중첩 구간의 시간은 서로 포함되므로 합산하지 않는다.
     * 메서드 반환 직후 종료 시각을 읽어 결과 분류 함수의 시간은 제외한다.
     */
    public record Event(String requestId, String stage, Long inputRevision, Long resultRevision, String outcome,
                        long startNanos, long endNanos, boolean success) {
        public long elapsedNanos() {
            return endNanos - startNanos;
        }
    }
}

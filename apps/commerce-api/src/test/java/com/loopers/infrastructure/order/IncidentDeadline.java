package com.loopers.infrastructure.order;

import java.time.Duration;
import java.util.function.LongSupplier;

/** 실행 전체의 monotonic 기한(checkout-incident §8.2·§9.2). 벽시계 변경에 영향받지 않는다. */
final class IncidentDeadline {

    private final LongSupplier nanoTime;
    private final long deadlineNanos;

    private IncidentDeadline(LongSupplier nanoTime, long deadlineNanos) {
        this.nanoTime = nanoTime;
        this.deadlineNanos = deadlineNanos;
    }

    static IncidentDeadline after(Duration budget) {
        return after(budget, System::nanoTime);
    }

    static IncidentDeadline after(Duration budget, LongSupplier nanoTime) {
        return new IncidentDeadline(nanoTime, nanoTime.getAsLong() + budget.toNanos());
    }

    /** 남은 시간. 지났으면 0 이다. */
    Duration remaining() {
        long left = deadlineNanos - nanoTime.getAsLong();
        return left <= 0 ? Duration.ZERO : Duration.ofNanos(left);
    }

    boolean expired() {
        return remaining().isZero();
    }

    long nowNanos() {
        return nanoTime.getAsLong();
    }
}

package com.loopers.infrastructure.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

final class OrderEventPublishMetrics {

    private static final String NAME = "order.event.publish";

    private final MeterRegistry meterRegistry;

    OrderEventPublishMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    double successes() {
        return sum("success");
    }

    double failures() {
        return sum("failure");
    }

    double failuresCausedBy(Class<? extends Throwable> cause) {
        Counter counter = meterRegistry.find(NAME)
            .tags("type", "OrderConfirmed", "result", "failure", "cause", cause.getSimpleName())
            .counter();
        return counter == null ? 0 : counter.count();
    }

    private double sum(String result) {
        return meterRegistry.find(NAME).tags("type", "OrderConfirmed", "result", result).counters().stream()
            .mapToDouble(Counter::count)
            .sum();
    }
}

package com.loopers.config.experiment;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

/** Per load-run original-list calls, including requests not selected for detailed tracing. */
@Component
@Profile("experiment")
public class ExperimentOriginMetrics {
    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, State> runs = new ConcurrentHashMap<>();
    public ExperimentOriginMetrics(MeterRegistry registry) { this.registry = registry; }
    @FunctionalInterface public interface OriginalRead { Object get() throws Throwable; }
    private record State(AtomicInteger active, AtomicInteger maximum) { }

    public Object record(String run, OriginalRead operation) throws Throwable {
        return record(run, "origin", operation);
    }
    public Object recordStock(String run, OriginalRead operation) throws Throwable {
        return record(run, "stock.origin", operation);
    }
    private Object record(String run, String kind, OriginalRead operation) throws Throwable {
        if (run == null || !run.matches("[0-9]{8}T[0-9]{6}-(warmup|measure)-[0-9]+-[a-z0-9]{6}")) return operation.get();
        State state = runs.computeIfAbsent(kind + ":" + run, key -> {
            var active = new AtomicInteger();
            var maximum = new AtomicInteger();
            registry.gauge("experiment." + kind + ".active", java.util.List.of(io.micrometer.core.instrument.Tag.of("run", run)), active);
            registry.gauge("experiment." + kind + ".maximum", java.util.List.of(io.micrometer.core.instrument.Tag.of("run", run)), maximum);
            return new State(active, maximum);
        });
        int active = state.active().incrementAndGet();
        state.maximum().accumulateAndGet(active, Math::max);
        registry.counter("experiment." + kind + ".loads", "run", run).increment();
        if (active > 1) registry.counter("experiment." + kind + ".overlapping", "run", run).increment();
        try { return operation.get(); }
        catch (Throwable failure) {
            registry.counter("experiment." + kind + ".errors", "run", run).increment();
            throw failure;
        } finally { state.active().decrementAndGet(); }
    }
}

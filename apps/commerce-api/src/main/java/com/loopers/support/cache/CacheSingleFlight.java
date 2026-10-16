package com.loopers.support.cache;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Process-local refill coordination. Followers re-read; composed responses are never shared. */
@Component
public class CacheSingleFlight {
    record Key(String cacheName, String cacheKey) { }
    record Result<T>(boolean owner, T value) { }
    private final ConcurrentHashMap<Key, CompletableFuture<Void>> flights = new ConcurrentHashMap<>();

    <T> Result<T> execute(Key key, Supplier<T> refill) {
        var completion = new CompletableFuture<Void>();
        var existing = flights.putIfAbsent(key, completion);
        if (existing != null) {
            try { existing.get(); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while awaiting cache refill", e);
            } catch (ExecutionException e) {
                throw propagate(e.getCause());
            }
            return new Result<>(false, null);
        }
        try {
            T result = refill.get();
            return new Result<>(true, result);
        } finally {
            flights.remove(key, completion);
            // A failing composed request must not leak its error to another route/user.
            completion.complete(null);
        }
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException("Cache refill failed", failure);
    }
}

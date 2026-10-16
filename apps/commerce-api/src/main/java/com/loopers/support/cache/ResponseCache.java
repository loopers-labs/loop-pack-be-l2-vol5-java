package com.loopers.support.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class ResponseCache {
    private final ThreadLocal<java.util.Map<String, String>> probeRevisions = new ThreadLocal<>();
    private final ThreadLocal<Boolean> hitOnly = new ThreadLocal<>();

    /** Probe the entire composed response without loading any original data. */
    <T> T hitOnly(Supplier<T> operation) {
        Boolean previous = hitOnly.get();
        var previousRevisions = probeRevisions.get();
        probeRevisions.set(new java.util.HashMap<>());
        hitOnly.set(true);
        try { return operation.get(); }
        finally {
            if (previousRevisions == null) probeRevisions.remove();
            else probeRevisions.set(previousRevisions);
            if (previous == null) hitOnly.remove();
            else hitOnly.set(previous);
        }
    }

    private <T> T loadOriginal(Supplier<T> loader, CacheSingleFlight.Key key) {
        requireOriginalRead(key);
        return loader.get();
    }

    private void requireOriginalRead(CacheSingleFlight.Key key) {
        if (Boolean.TRUE.equals(hitOnly.get())) throw new CacheMiss(key);
    }

    /** Internal control flow: restart with a DB snapshot. */
    static final class CacheMiss extends RuntimeException {
        final CacheSingleFlight.Key key;
        private CacheMiss(CacheSingleFlight.Key key) { super(null, null, false, false); this.key = key; }
    }

    private final CacheVersions versions;
    private final CachedJson cached;
    private final boolean enabled;
    private final ObjectMapper json;
    public ResponseCache(CachedJson cached, @org.springframework.beans.factory.annotation.Value("${catalog.cache.enabled:false}") boolean enabled, ObjectMapper json, CacheVersions versions) {
        this.versions = versions; this.cached = cached; this.enabled = enabled; this.json = json;
    }
    public boolean isEnabled() { return enabled; }
    public record Pair<A, B>(A first, B second) { }

    /** Each item has its own key; missing items are loaded in one DB batch. */
    public record Batch<T>(String cacheName, java.util.List<Long> ids, TypeReference<T> type,
                           java.util.function.Function<java.util.List<Long>, java.util.List<T>> loader,
                           java.util.function.Function<T, Long> id) { }

    private <T> java.util.List<String> keys(Batch<T> batch, CacheVersions.Snapshot snapshot) {
        return batch.ids().stream().map(id -> {
            String key = CacheKeys.item(batch.cacheName(), id);
            return snapshot.key(key, CacheKeys.groups(batch.cacheName(), key));
        }).toList();
    }
    private CacheVersions.Snapshot snapshot(Batch<?>... batches) {
        var groups = new java.util.LinkedHashSet<String>();
        for (var batch : batches) for (Long id : batch.ids()) {
            java.util.Collections.addAll(groups, CacheKeys.groups(batch.cacheName(), CacheKeys.item(batch.cacheName(), id)));
        }
        return snapshot(groups.toArray(String[]::new));
    }

    private CacheVersions.Snapshot snapshot(String... groups) {
        var snapshot = versions.snapshot(groups);
        var seen = probeRevisions.get();
        if (seen != null) {
            snapshot.values().forEach((group, revision) -> {
                String previous = seen.putIfAbsent(group, revision);
                // A composed hit crossed a catalog change: restart the entire read in a DB snapshot.
                if (previous != null && !previous.equals(revision)) throw new CacheMiss(null);
            });
        }
        return snapshot;
    }

    public <T> java.util.List<T> readBatch(Batch<T> batch) {
        if (batch.ids().isEmpty()) return java.util.List.of();
        if (!isEnabled()) return loadOriginal(() -> batch.loader().apply(batch.ids()), null);
        var keys = keys(batch, snapshot(batch));
        return resolveBatch(batch, keys, cached.loadMany(java.util.Collections.nCopies(keys.size(), batch.cacheName()), keys));
    }

    public <A, B> Pair<java.util.List<A>, java.util.List<B>> readBatches(Batch<A> first, Batch<B> second) {
        if (!isEnabled()) return new Pair<>(loadOriginal(() -> first.loader().apply(first.ids()), null),
            loadOriginal(() -> second.loader().apply(second.ids()), null));
        var snapshot = snapshot(first, second);
        var firstKeys = keys(first, snapshot);
        var secondKeys = keys(second, snapshot);
        var allKeys = new java.util.ArrayList<>(firstKeys); allKeys.addAll(secondKeys);
        var names = new java.util.ArrayList<>(java.util.Collections.nCopies(firstKeys.size(), first.cacheName()));
        names.addAll(java.util.Collections.nCopies(secondKeys.size(), second.cacheName()));
        var values = cached.loadMany(names, allKeys);
        return new Pair<>(resolveBatch(first, firstKeys, values.subList(0, firstKeys.size())),
            resolveBatch(second, secondKeys, values.subList(firstKeys.size(), values.size())));
    }

    private <T> java.util.List<T> resolveBatch(Batch<T> batch, java.util.List<String> keys, java.util.List<String> values) {
        var found = new java.util.HashMap<Long, T>();
        var missing = new java.util.ArrayList<Long>();
        var corrupt = new java.util.HashSet<Long>();
        for (int i = 0; i < keys.size(); i++) {
            Long id = batch.ids().get(i);
            String value = values.get(i);
            if (value != null) {
                try {
                    T item = json.readValue(value, batch.type());
                    if (item != null && id.equals(batch.id().apply(item))) { found.put(id, item); continue; }
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) { /* reload malformed entry */ }
                requireOriginalRead(new CacheSingleFlight.Key(batch.cacheName(), keys.get(i)));
                cached.evict(batch.cacheName(), keys.get(i));
                corrupt.add(id);
            }
            missing.add(id);
        }
        if (!missing.isEmpty()) {
            String firstKey = keys.get(batch.ids().indexOf(missing.get(0)));
            var loaded = loadOriginal(() -> batch.loader().apply(java.util.List.copyOf(missing)), new CacheSingleFlight.Key(batch.cacheName(), firstKey));
            for (T item : loaded) {
                Long id = batch.id().apply(item);
                if (!missing.contains(id)) throw new IllegalStateException("Unexpected product from batch loader");
                found.put(id, item);
                if (!corrupt.contains(id)) {
                    try { cached.store(batch.cacheName(), keys.get(batch.ids().indexOf(id)), json.writeValueAsString(item)); }
                    catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
                }
            }
        }
        return batch.ids().stream().filter(found::containsKey).map(found::get).toList();
    }

    public <T> T read(String cacheName, String apiKey, TypeReference<T> type,
                      Supplier<T> loader) {
        if (!isEnabled()) return loadOriginal(loader, null);
        String[] groups = CacheKeys.groups(cacheName, apiKey);
        String key = snapshot(groups).key(apiKey, groups);
        String value = cached.load(cacheName, key, () -> {
            try { return json.writeValueAsString(loadOriginal(loader, new CacheSingleFlight.Key(cacheName, key))); }
            catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
        });
        try { return json.readValue(value, type); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            requireOriginalRead(new CacheSingleFlight.Key(cacheName, key));
            cached.evict(cacheName, key);
            return loadOriginal(loader, new CacheSingleFlight.Key(cacheName, key));
        }
    }
}

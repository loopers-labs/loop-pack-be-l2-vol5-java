package com.loopers.support.cache;

import com.loopers.config.redis.RedisConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Basic after-commit invalidation. Failed deletion and late fills are measured, not hidden. */
@Component
public class WriteInvalidation {
    public static final String KEY_PREFIX = CacheKeys.VERSION_PREFIX;
    private final boolean enabled;
    private final RedisTemplate<String, String> redis;
    private final MeterRegistry metrics;

    public WriteInvalidation(@Value("${experiment.catalog-write-invalidate.enabled:false}") boolean enabled,
                             @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis,
                             MeterRegistry metrics) {
        this.enabled = enabled; this.redis = redis; this.metrics = metrics;
    }
    @org.springframework.beans.factory.annotation.Autowired
    void validateExperiment(org.springframework.core.env.Environment environment) {
        if (enabled && !environment.matchesProfiles("experiment")) {
            throw new IllegalStateException("Write invalidation requires experiment profile");
        }
    }
    public boolean enabled() { return enabled; }
    public void changed(String group) {
        if (!enabled) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Invalidation must be registered inside the write transaction");
        }
        for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof Pending pending && pending.owner == this) {
                pending.groups.add(group); return;
            }
        }
        var pending = new Pending(); pending.groups.add(group);
        TransactionSynchronizationManager.registerSynchronization(pending);
    }
    private final class Pending implements TransactionSynchronization {
        private final WriteInvalidation owner = WriteInvalidation.this;
        private final Set<String> groups = new LinkedHashSet<>();
        @Override public void afterCommit() {
            Set<String> patterns = new LinkedHashSet<>();
            for (String group : groups) {
                if (group.equals(CacheKeys.CATALOG_GROUP)) {
                    CacheKeys.NAMES.forEach(bucket -> patterns.add(bucket + "::" + KEY_PREFIX + "*"));
                } else if (group.startsWith(CacheKeys.STOCK_GROUP + ":")) {
                    patterns.add(CacheKeys.STOCKS + "::" + CacheKeys.item(CacheKeys.STOCKS, Long.valueOf(group.substring(6))));
                } else if (group.startsWith("likes:")) {
                    patterns.add(CacheKeys.MY_LIKES + "::" + KEY_PREFIX + "users/" + group.substring(6) + "/likes?*");
                } else throw new IllegalArgumentException("Unknown invalidation group");
            }
            for (String pattern : patterns) {
                try { deletePattern(pattern); metrics.counter("catalog.cache.invalidation", "outcome", "success").increment(); }
                catch (RuntimeException e) {
                    // DB already committed. Retain the failure signal; TTL is the baseline recovery path.
                    metrics.counter("catalog.cache.invalidation", "outcome", "failure").increment();
                }
            }
        }
    }
    public void deletePattern(String pattern) {
        if (!pattern.contains("*")) { unlink(List.of(pattern)); return; }
        try (var cursor = redis.scan(ScanOptions.scanOptions().match(pattern).count(1000).build())) {
            List<String> batch = new ArrayList<>(1000);
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() == 1000) { unlink(batch); batch.clear(); }
            }
            if (!batch.isEmpty()) unlink(batch);
        }
    }
    private void unlink(List<String> keys) {
        Long deleted = redis.unlink(keys);
        if (deleted != null) metrics.counter("catalog.cache.invalidation.keys").increment(deleted);
    }
}

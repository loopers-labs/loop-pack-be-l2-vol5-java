package com.loopers.support.cache;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** DB changes and their invalidation token commit or roll back together. */
@Component
public class CacheVersions {
    private final JdbcTemplate jdbc;
    private final WriteInvalidation invalidation;
    public CacheVersions(JdbcTemplate jdbc, WriteInvalidation invalidation) {
        this.jdbc = jdbc;
        this.invalidation = invalidation;
    }
    public Snapshot snapshot(String... groups) {
        if (invalidation.enabled()) return new Snapshot(Map.of(), true);
        var values = new java.util.LinkedHashMap<String, String>();
        for (String group : groups) values.put(group, "0");
        if (!values.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(values.size(), "?"));
            jdbc.query("select namespace, revision from read_cache_revision where namespace in (" + placeholders + ")",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> values.put(row.getString("namespace"), row.getString("revision")),
                values.keySet().toArray());
        }
        return new Snapshot(Map.copyOf(values), false);
    }
    public String key(String key, String... groups) { return snapshot(groups).key(key, groups); }
    public record Snapshot(Map<String, String> values, boolean writeInvalidate) {
        public String key(String key, String... groups) {
            if (writeInvalidate) return key;
            var result = new StringBuilder(key);
            for (String group : groups) {
                if (!values.containsKey(group)) throw new IllegalArgumentException("Revision not loaded: " + group);
                result.append(":rev=").append(values.get(group));
            }
            return result.toString();
        }
    }
    public void changed(String group) {
        if (invalidation.enabled()) { invalidation.changed(group); return; }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Cache revision requires the database write transaction");
        }
        jdbc.update("insert into read_cache_revision(namespace, revision) values (?, ?) "
            + "on duplicate key update revision = values(revision)", group, UUID.randomUUID().toString());
    }
}

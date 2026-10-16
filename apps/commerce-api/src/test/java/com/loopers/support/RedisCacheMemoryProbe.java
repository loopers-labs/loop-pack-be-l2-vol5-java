package com.loopers.support;

import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.async.RedisServerAsyncCommands;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Read-only, test-only observer. Use a dedicated client with bounded connection/command timeouts.
 * SCAN is incremental and this sample is not atomic: keys can expire or change while it is collected.
 * Per-key MEMORY USAGE is distinct from whole-process INFO MEMORY, which includes server overhead.
 * Read commands may trigger Redis lazy expiration; expired_keys is therefore observer-inclusive.
 */
public final class RedisCacheMemoryProbe {
    private static final long SNAPSHOT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final int MAX_SCANNED_KEYS = 10_000;
    private final StringRedisTemplate redis;

    public RedisCacheMemoryProbe(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis);
    }

    public Snapshot snapshot(String keyPrefix) {
        validatePrefix(keyPrefix);
        long started = System.nanoTime();
        return Objects.requireNonNull(redis.execute((RedisCallback<Snapshot>) connection ->
            measure(new ConnectionSource(connection, started), keyPrefix, started)));
    }

    static Snapshot measure(ReadOnlySource source, String keyPrefix, long started) {
        validatePrefix(keyPrefix);
        List<String> scanned = source.scan(keyPrefix);
        LinkedHashSet<String> unique = new LinkedHashSet<>(scanned);
        List<Entry> entries = new ArrayList<>();
        int vanished = 0;
        long allocatedBytes = 0;
        long payloadBytes = 0;
        for (String key : unique) {
            checkDeadline(started);
            if (!key.startsWith(keyPrefix)) {
                throw new IllegalStateException("SCAN returned a key outside the requested prefix");
            }
            long before = requiredNumber(source.ttlMillis(key), "PTTL");
            if (before == -2) {
                vanished++;
                continue;
            }
            validateTtl(before);
            Long allocated = source.allocatedBytes(key);
            if (allocated == null) {
                vanished++;
                continue;
            }
            long payload = requiredNumber(source.payloadBytes(key), "STRLEN");
            long after = requiredNumber(source.ttlMillis(key), "PTTL");
            if (after == -2) {
                vanished++;
                continue;
            }
            validateTtl(after);
            if (allocated < 0 || payload < 0) {
                throw new IllegalStateException("Redis returned negative byte counts");
            }
            entries.add(new Entry(key, revision(key), after, allocated, payload));
            allocatedBytes = Math.addExact(allocatedBytes, allocated);
            payloadBytes = Math.addExact(payloadBytes, payload);
        }
        entries.sort(java.util.Comparator.comparing(Entry::key));
        checkDeadline(started);
        ServerMemory server = serverMemory(source.info("memory"), source.info("stats"), source.info("server"));
        checkDeadline(started);
        return new Snapshot(keyPrefix, unique.size(), scanned.size() - unique.size(), vanished,
            entries.size(), allocatedBytes, payloadBytes, List.copyOf(entries), server,
            System.nanoTime() - started);
    }

    static ServerMemory serverMemory(Properties memory, Properties stats, Properties server) {
        return new ServerMemory(property(memory, "used_memory"), property(memory, "used_memory_dataset"),
            property(memory, "used_memory_rss"), property(stats, "expired_keys"), property(stats, "evicted_keys"),
            property(memory, "maxmemory"), server == null ? null : server.getProperty("redis_version"));
    }

    private static Long property(Properties values, String name) {
        if (values == null || values.getProperty(name) == null) {
            return null;
        }
        return Long.valueOf(values.getProperty(name));
    }

    private static Long revision(String key) {
        int marker = key.lastIndexOf(":rev=");
        if (marker < 0) {
            return null;
        }
        try {
            long value = Long.parseLong(key.substring(marker + 5));
            return value >= 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static long requiredNumber(Long value, String command) {
        if (value == null) {
            throw new IllegalStateException(command + " returned no numeric result");
        }
        return value;
    }

    private static void validateTtl(long value) {
        if (value < -1) {
            throw new IllegalStateException("Redis returned an invalid PTTL: " + value);
        }
    }

    private static void validatePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("An explicit cache key prefix is required");
        }
    }

    private static void checkDeadline(long started) {
        if (System.nanoTime() - started > SNAPSHOT_TIMEOUT_NANOS) {
            throw new IllegalStateException("Redis memory snapshot exceeded its 10 second budget");
        }
    }

    static String scanPattern(String prefix) {
        validatePrefix(prefix);
        return prefix.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?")
            .replace("[", "\\[").replace("]", "\\]") + "*";
    }

    static Long awaitMemoryUsage(RedisFuture<Long> result) {
        try {
            return result.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            result.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Redis memory snapshot interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            result.cancel(true);
            throw new IllegalStateException("MEMORY USAGE failed; measurement is incomplete", exception);
        }
    }

    public record Snapshot(String keyPrefix, int scannedKeys, int duplicateScanKeys, int vanishedDuringMeasurement,
                           int liveKeys, long allocatedBytes, long payloadBytes, List<Entry> entries,
                           ServerMemory server, long elapsedNanos) {
    }

    public record Entry(String key, Long revision, long ttlMillis, long allocatedBytes, long payloadBytes) {
    }

    /** Values cover the entire Redis server, not just keyPrefix; missing INFO fields remain null. */
    public record ServerMemory(Long usedMemoryBytes, Long usedMemoryDatasetBytes, Long usedMemoryRssBytes,
                               Long expiredKeys, Long evictedKeys, Long maxMemoryBytes, String redisVersion) {
    }

    interface ReadOnlySource {
        List<String> scan(String keyPrefix);

        Long ttlMillis(String key);

        Long allocatedBytes(String key);

        Long payloadBytes(String key);

        Properties info(String section);
    }

    private static final class ConnectionSource implements ReadOnlySource {
        private final RedisConnection connection;
        private final long started;

        private ConnectionSource(RedisConnection connection, long started) {
            this.connection = connection;
            this.started = started;
        }

        @Override
        public List<String> scan(String keyPrefix) {
            List<String> result = new ArrayList<>();
            ScanOptions options = ScanOptions.scanOptions().match(scanPattern(keyPrefix)).count(100).build();
            try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                while (cursor.hasNext()) {
                    checkDeadline(started);
                    if (result.size() >= MAX_SCANNED_KEYS) {
                        throw new IllegalStateException("Redis memory snapshot exceeded its SCAN key budget");
                    }
                    result.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return result;
        }

        @Override
        public Long ttlMillis(String key) {
            return connection.keyCommands().pTtl(bytes(key));
        }

        @Override
        @SuppressWarnings("unchecked")
        public Long allocatedBytes(String key) {
            Object nativeConnection = connection.getNativeConnection();
            if (!(nativeConnection instanceof RedisServerAsyncCommands<?, ?>)) {
                throw new IllegalStateException("This test observer requires a Lettuce async connection");
            }
            RedisServerAsyncCommands<byte[], byte[]> commands =
                (RedisServerAsyncCommands<byte[], byte[]>) nativeConnection;
            return awaitMemoryUsage(commands.memoryUsage(bytes(key)));
        }

        @Override
        public Long payloadBytes(String key) {
            return connection.stringCommands().strLen(bytes(key));
        }

        @Override
        public Properties info(String section) {
            return connection.serverCommands().info(section);
        }

        private byte[] bytes(String key) {
            return key.getBytes(StandardCharsets.UTF_8);
        }
    }
}

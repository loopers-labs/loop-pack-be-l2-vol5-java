package com.loopers.support.cache;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import org.springframework.data.redis.cache.RedisCacheWriter;

/** A fresh TTL for each cache write; cache hits do not renew it. */
public final class ReadCacheTtl implements RedisCacheWriter.TtlFunction {
    public static final long BASE_SECONDS = 300;
    public static final long JITTER_SECONDS = 30;
    private final LongSupplier jitter;
    private final long latestFirstSeconds;

    public ReadCacheTtl() {
        this(0, false);
    }

    public ReadCacheTtl(long latestFirstSeconds, boolean experimentProfile) {
        if (latestFirstSeconds < 0 || (latestFirstSeconds > 0 && !experimentProfile)) {
            throw new IllegalArgumentException("Short latest TTL requires experiment profile and a positive duration");
        }
        this.latestFirstSeconds = latestFirstSeconds;
        this.jitter = () -> ThreadLocalRandom.current().nextLong(-JITTER_SECONDS, JITTER_SECONDS + 1);
    }

    ReadCacheTtl(LongSupplier jitter) {
        this.jitter = jitter;
        this.latestFirstSeconds = 0;
    }

    @Override
    public Duration getTimeToLive(Object key, Object value) {
        if (latestFirstSeconds > 0 && key instanceof String text
            && (text.equals(CacheKeys.products("latest", null, 0, 20))
                || text.startsWith(CacheKeys.products("latest", null, 0, 20) + ":rev="))) {
            return Duration.ofSeconds(latestFirstSeconds);
        }
        return Duration.ofSeconds(BASE_SECONDS + jitter.getAsLong());
    }
}

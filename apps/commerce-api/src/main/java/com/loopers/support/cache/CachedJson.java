package com.loopers.support.cache;

import java.util.function.Supplier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

@Component
public class CachedJson {
    private final org.springframework.data.redis.core.RedisTemplate<String, String> redis;
    private final org.springframework.cache.CacheManager manager;
    private final io.micrometer.core.instrument.MeterRegistry metrics;

    public CachedJson(@org.springframework.beans.factory.annotation.Qualifier(com.loopers.config.redis.RedisConfig.REDIS_TEMPLATE_MASTER)
                      org.springframework.data.redis.core.RedisTemplate<String, String> redis,
                      org.springframework.cache.CacheManager manager, io.micrometer.core.instrument.MeterRegistry metrics) {
        this.redis = redis; this.manager = manager; this.metrics = metrics;
    }

    /** Spring Cache와 동일한 prefix/문자열 직렬화를 사용하여 여러 버킷을 한 명령으로 읽는다. */
    public java.util.List<String> loadMany(java.util.List<String> names, java.util.List<String> keys) {
        var physicalKeys = new java.util.ArrayList<String>();
        for (int i = 0; i < names.size(); i++) {
            if (!CacheKeys.NAMES.contains(names.get(i))) throw new IllegalArgumentException("Unknown read cache");
            var cache = (org.springframework.data.redis.cache.RedisCache) manager.getCache(names.get(i));
            physicalKeys.add(java.util.Objects.requireNonNull(cache).getCacheConfiguration().getKeyPrefixFor(names.get(i)) + keys.get(i));
        }
        try { return redis.opsForValue().multiGet(physicalKeys); }
        catch (RuntimeException e) {
            metrics.counter("catalog.cache.errors", "operation", "get").increment();
            return java.util.Collections.nCopies(keys.size(), null);
        }
    }

    @org.springframework.cache.annotation.CachePut(cacheResolver = "readCacheResolver", key = "#key")
    public String store(String cacheName, String key, String value) { return value; }

    @Cacheable(cacheResolver = "readCacheResolver", key = "#key")
    public String load(String cacheName, String key, Supplier<String> loader) { return loader.get(); }
    @org.springframework.cache.annotation.CacheEvict(cacheResolver = "readCacheResolver", key = "#key")
    public void evict(String cacheName, String key) { }
}

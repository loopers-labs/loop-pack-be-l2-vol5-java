package com.loopers.config.cache;

import com.loopers.support.cache.CacheKeys;
import com.loopers.support.cache.ReadCacheTtl;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration(proxyBeanMethods = false)
@EnableCaching
public class ReadCacheConfiguration implements CachingConfigurer {
    private final io.micrometer.core.instrument.MeterRegistry metrics;

    public ReadCacheConfiguration(io.micrometer.core.instrument.MeterRegistry metrics) { this.metrics = metrics; }
    @Bean
    CacheManager cacheManager(@Qualifier("redisConnectionMaster") RedisConnectionFactory connection,
        @org.springframework.beans.factory.annotation.Value("${experiment.latest-first-ttl-seconds:0}") long latestFirstSeconds,
        org.springframework.core.env.Environment environment) {
        var defaults = RedisCacheConfiguration.defaultCacheConfig().entryTtl(new ReadCacheTtl(latestFirstSeconds, environment.matchesProfiles("experiment"))).disableCachingNullValues()
            .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()));
        var configurations = new java.util.HashMap<String, RedisCacheConfiguration>();
        CacheKeys.NAMES.forEach(name -> configurations.put(name, defaults));
        return RedisCacheManager.builder(RedisCacheWriter.nonLockingRedisCacheWriter(connection, BatchStrategies.scan(1000)))
            .cacheDefaults(defaults)
            .withInitialCacheConfigurations(configurations).build();
    }
    @Bean
    CacheResolver readCacheResolver(CacheManager manager) {
        return context -> {
            String name = (String) context.getArgs()[0];
            if (!CacheKeys.NAMES.contains(name)) throw new IllegalArgumentException("Unknown read cache");
            return List.of(java.util.Objects.requireNonNull(manager.getCache(name)));
        };
    }
    @Override
    public CacheErrorHandler errorHandler() {
        // Redis failures fall back to the original database read.
        return new CacheErrorHandler() {
            public void handleCacheGetError(RuntimeException e, Cache c, Object k) { error("get"); }
            public void handleCachePutError(RuntimeException e, Cache c, Object k, Object v) { error("put"); }
            public void handleCacheEvictError(RuntimeException e, Cache c, Object k) { error("evict"); }
            public void handleCacheClearError(RuntimeException e, Cache c) { error("clear"); }
        };
    }
    private void error(String operation) {
        metrics.counter("catalog.cache.errors", "operation", operation).increment();
    }
}

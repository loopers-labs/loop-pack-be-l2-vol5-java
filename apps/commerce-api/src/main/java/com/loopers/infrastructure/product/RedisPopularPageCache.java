package com.loopers.infrastructure.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.product.CachedFirstPage;
import com.loopers.domain.product.PopularPage;
import com.loopers.domain.product.PopularPageCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
@Slf4j
public class RedisPopularPageCache implements PopularPageCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final String prefix;
    private final Duration ttl;

    public RedisPopularPageCache(@Qualifier("productCacheRedisTemplate") StringRedisTemplate redis, ObjectMapper mapper,
        @Value("${commerce.cache.popular.prefix:commerce:popular-page:v1}") String prefix,
        @Value("${commerce.cache.popular.ttl-seconds:60}") long ttlSeconds) {
        if (ttlSeconds <= 0) { throw new IllegalArgumentException("Cache TTL must be positive"); }
        this.redis = redis;
        this.mapper = mapper;
        this.prefix = prefix;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    @Override
    public Optional<PopularPage> get(Long brandId) {
        try {
            String value = redis.opsForValue().get(key(brandId));
            if (value == null) { return Optional.empty(); }
            PopularPage result = mapper.readValue(value, PopularPage.class);
            if (result == null || result.ids().size() > CachedFirstPage.SIZE
                || result.ids().stream().anyMatch(id -> id <= 0)
                || result.ids().stream().distinct().count() != result.ids().size()) {
                return Optional.empty();
            }
            return Optional.of(result);
        } catch (Exception exception) {
            log.warn("Popular page cache read failed: {}", exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public void put(Long brandId, PopularPage page) {
        try {
            redis.opsForValue().set(key(brandId), mapper.writeValueAsString(page), ttl);
        } catch (Exception exception) {
            log.warn("Popular page cache fill failed: {}", exception.getClass().getSimpleName());
        }
    }

    @Override
    public void evict(Long brandId) {
        try { redis.delete(key(brandId)); }
        catch (Exception exception) {
            log.warn("Popular page cache eviction failed: {}", exception.getClass().getSimpleName());
        }
    }

    private String key(Long brandId) { return prefix + ":brand:" + brandId + ":sort:popular:page:" + CachedFirstPage.PAGE + ":size:" + CachedFirstPage.SIZE; }
}

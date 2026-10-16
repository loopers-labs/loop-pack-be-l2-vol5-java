package com.loopers.infrastructure.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.product.PricePage;
import com.loopers.domain.product.PricePageCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.Duration;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "commerce.cache.price.provider", havingValue = "redis", matchIfMissing = true)
@Slf4j
public class RedisPricePageCache implements PricePageCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final String prefix;
    private final Duration ttl;
    public RedisPricePageCache(@Qualifier("productCacheRedisTemplate") StringRedisTemplate redis, ObjectMapper mapper,
        @Value("${commerce.cache.price.prefix:commerce:price-page:v3}") String prefix,
        @Value("${commerce.cache.price.ttl-seconds:300}") long ttlSeconds) {
        if (ttlSeconds <= 0) { throw new IllegalArgumentException("Cache TTL must be positive"); }
        this.redis = redis;
        this.mapper = mapper;
        this.prefix = prefix;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }
    @Override
    public Optional<PricePage> get(Long brandId, int page, int size) {
        try {
            String value = redis.opsForValue().get(key(brandId, page, size));
            if (value == null) { return Optional.empty(); }
            PricePage result = mapper.readValue(value, PricePage.class);
            if (result == null || result.items() == null || result.items().size() > size
                || result.items().stream().anyMatch(i -> i == null || i.id() == null || i.name() == null
                    || i.createdAt() == null || i.price() <= 0)
                || result.items().stream().map(PricePage.Item::id).distinct().count() != result.items().size()) {
                return Optional.empty();
            }
            return Optional.of(result);
        } catch (Exception exception) {
            log.warn("Price page cache read failed: {}", exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }
    @Override
    public void put(Long brandId, int page, int size, PricePage value) {
        try {
            redis.opsForValue().set(key(brandId, page, size), mapper.writeValueAsString(value), ttl);
        } catch (Exception exception) {
            log.warn("Price page cache fill failed: {}", exception.getClass().getSimpleName());
        }
    }
    @Override
    public void evict(Long brandId, int page, int size) {
        try {
            redis.delete(key(brandId, page, size));
        } catch (Exception exception) {
            log.warn("Price page cache eviction failed: {}", exception.getClass().getSimpleName());
        }
    }
    private String key(Long brandId, int page, int size) {
        return prefix + ":brand:" + brandId + ":sort:price_asc:page:" + page + ":size:" + size;
    }
}

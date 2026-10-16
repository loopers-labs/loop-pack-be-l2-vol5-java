package com.loopers.infrastructure.product;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.loopers.domain.product.PricePage;
import com.loopers.domain.product.PricePageCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/** Comparison adapter: invalidation is local to this application instance. */
@Component
@ConditionalOnProperty(name = "commerce.cache.price.provider", havingValue = "local")
public class LocalPricePageCache implements PricePageCache {
    private final Cache<Key, PricePage> entries;

    @Autowired
    public LocalPricePageCache(@Value("${commerce.cache.price.ttl-seconds:300}") long ttlSeconds,
        @Value("${commerce.cache.price.local-maximum-size:1000}") long maximumSize) {
        this(ttlSeconds, maximumSize, Ticker.systemTicker());
    }

    LocalPricePageCache(long ttlSeconds, long maximumSize, Ticker ticker) {
        if (ttlSeconds <= 0 || maximumSize <= 0) {
            throw new IllegalArgumentException("Cache TTL and maximum size must be positive");
        }
        entries = Caffeine.newBuilder().maximumSize(maximumSize)
            .expireAfterWrite(Duration.ofSeconds(ttlSeconds)).ticker(ticker).build();
    }

    @Override
    public Optional<PricePage> get(Long brandId, int page, int size) {
        return Optional.ofNullable(entries.getIfPresent(new Key(brandId, page, size)));
    }

    @Override
    public void put(Long brandId, int page, int size, PricePage value) {
        entries.put(new Key(brandId, page, size), value);
    }

    @Override
    public void evict(Long brandId, int page, int size) {
        entries.invalidate(new Key(brandId, page, size));
    }

    private record Key(Long brandId, int page, int size) {}
}

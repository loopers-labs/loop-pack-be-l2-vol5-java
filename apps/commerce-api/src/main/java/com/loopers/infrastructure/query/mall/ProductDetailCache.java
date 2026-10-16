package com.loopers.infrastructure.query.mall;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.loopers.application.mall.query.ProductDetailView;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 단일 JVM의 고객 상세 응답만 저장하며 DB 조회 중에는 무효화 잠금을 잡지 않는다.
@Component
public class ProductDetailCache {
    private final boolean enabled;
    private final Cache<Long, ProductDetailView> cache;
    private final Counter dbLoads;
    private final Object lock = new Object();
    private long generation;

    @Autowired
    public ProductDetailCache(ProductDetailCacheProperties properties, MeterRegistry registry) {
        this(properties, registry, Ticker.systemTicker());
    }

    ProductDetailCache(ProductDetailCacheProperties properties, MeterRegistry registry, Ticker ticker) {
        enabled = properties.enabled();
        cache = Caffeine.newBuilder()
            .maximumSize(enabled ? properties.maximumSize() : 1000)
            .expireAfterWrite(enabled ? properties.ttl() : Duration.ofSeconds(30))
            .ticker(ticker)
            .recordStats()
            .build();
        CaffeineCacheMetrics.monitor(registry, cache, "product-detail");
        dbLoads = Counter.builder("product.detail.db.loads")
            .description("Customer product detail DB loads including misses and cache bypasses")
            .register(registry);
    }

    public Optional<ProductDetailView> find(long productId, Supplier<Optional<ProductDetailView>> loader) {
        // 이미 시작된 트랜잭션의 미커밋 값·이전 스냅샷을 공유 캐시에 섞지 않는다.
        if (!enabled || TransactionSynchronizationManager.isActualTransactionActive()) {
            return load(loader);
        }

        long observedGeneration;
        synchronized (lock) {
            ProductDetailView cached = cache.getIfPresent(productId);
            if (cached != null) {
                return Optional.of(cached);
            }
            observedGeneration = generation;
        }

        Optional<ProductDetailView> loaded = load(loader);
        synchronized (lock) {
            // 조회 중 어떤 쓰기라도 커밋됐다면 늦게 도착한 응답을 새 요청에 재사용하지 않는다.
            if (generation == observedGeneration) {
                loaded.ifPresent(value -> cache.put(productId, value));
            }
        }
        return loaded;
    }

    public void invalidateProducts(Set<Long> productIds) {
        synchronized (lock) {
            generation++;
            cache.invalidateAll(productIds);
        }
    }

    public void invalidateBrand(long brandId) {
        synchronized (lock) {
            generation++;
            cache.asMap().entrySet().removeIf(entry -> entry.getValue().brand().brandId() == brandId);
        }
    }

    public void invalidateAll() {
        synchronized (lock) {
            generation++;
            cache.invalidateAll();
        }
    }

    void cleanUp() {
        cache.cleanUp();
    }

    long estimatedSize() {
        return cache.estimatedSize();
    }

    private Optional<ProductDetailView> load(Supplier<Optional<ProductDetailView>> loader) {
        dbLoads.increment();
        return loader.get();
    }
}

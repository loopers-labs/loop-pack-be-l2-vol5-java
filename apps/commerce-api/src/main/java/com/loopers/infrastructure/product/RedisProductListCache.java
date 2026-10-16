package com.loopers.infrastructure.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.config.ProductCacheRedisConfig;
import com.loopers.domain.product.ProductListCache;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.product.ProductView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 상품 목록 첫 페이지의 Redis 캐시 (4주차 docs/week4/cache-design.md).
 * 데이터 키에 범위(브랜드 · 전체) 버전을 넣고, 무효화는 버전을 올려 옛 키를 버린다. 옛 키는 TTL 로 정리된다.
 * 캐시 쪽의 어떤 실패도 원본 결과로 대신하고 쓰기 결과에 영향을 주지 않는다. 원본(loader)의 오류는 감싸지 않는다 (4.2)
 */
@Slf4j
@Component
public class RedisProductListCache implements ProductListCache {

    static final String ALL = "all";

    private final StringRedisTemplate master;
    private final StringRedisTemplate replicaPreferred;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    /** 데이터 키의 수명. 무효화하지 않는 좋아요 수가 오래된 값일 수 있는 상한이기도 하다 (2.2, 5) */
    private final Duration ttl;

    /** commit 뒤 버전 올리기에 실패한 범위. 일정에 따라 다시 올린다 (3.4) */
    private final Set<String> pendingScopes = ConcurrentHashMap.newKeySet();

    /** 저장 형식. 응답을 다시 만들 수 있도록 페이지 위치와 전체 건수를 함께 담는다 (2.2) */
    record CachedPage(List<ProductView> content, int page, int size, long totalElements) {}

    public RedisProductListCache(
        @Qualifier(ProductCacheRedisConfig.MASTER_TEMPLATE) StringRedisTemplate master,
        @Qualifier(ProductCacheRedisConfig.REPLICA_PREFERRED_TEMPLATE) StringRedisTemplate replicaPreferred,
        ObjectMapper objectMapper,
        Clock clock,
        @Value("${product-list-cache.ttl-seconds:60}") long ttlSeconds
    ) {
        this.master = master;
        this.replicaPreferred = replicaPreferred;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /** 버전 읽기 → 데이터 읽기 → (miss) 원본 읽기 → 읽은 버전에 저장. 원본의 오류는 감싸지 않고 그대로 나간다 (4.1) */
    @Override
    public Page<ProductView> getFirstPage(Long brandId, ProductSort sort, Supplier<Page<ProductView>> loader) {
        String scope = scopeOf(brandId);
        long version;
        try {
            version = currentVersion(scope);
        } catch (RuntimeException e) {
            log.warn("상품 목록 캐시 버전을 읽지 못해 원본으로 우회함. scope={}", scope, e);
            return loader.get();
        }

        String key = dataKey(scope, version, sort);
        try {
            String cached = replicaPreferred.opsForValue().get(key);
            if (cached != null) {
                return toPage(objectMapper.readValue(cached, CachedPage.class));
            }
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("상품 목록 캐시를 읽지 못해 원본으로 우회함. key={}", key, e);
            return loader.get();
        }

        Page<ProductView> page = loader.get();
        try {
            master.opsForValue().set(key, objectMapper.writeValueAsString(toCached(page)), ttl);
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("상품 목록 캐시에 저장하지 못함. key={}", key, e);
        }
        return page;
    }

    @Override
    public void invalidate(Long brandId) {
        List<String> scopes = List.of(scopeOf(brandId), ALL);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    scopes.forEach(RedisProductListCache.this::increaseVersion);
                }
            });
            return;
        }
        scopes.forEach(this::increaseVersion);
    }

    /**
     * 실패한 무효화를 다시 시도한다. 버전은 여러 번 올려도 해가 없어 중복을 따지지 않는다 (3.4).
     * 간격은 기본 1초이며, 장애 테스트가 재처리 시점을 직접 정할 수 있도록 설정으로 둔다
     */
    @Scheduled(fixedDelayString = "${product-list-cache.retry-delay-ms:1000}")
    void retryPendingInvalidations() {
        for (String scope : List.copyOf(pendingScopes)) {
            pendingScopes.remove(scope);
            increaseVersion(scope);
        }
    }

    /** 처음 기동했거나 버전 키가 사라졌으면 현재 시각(ms)으로 만든다. 0 · 1 로 다시 세면 예전 데이터 키와 번호가 겹친다 (3.5) */
    private long currentVersion(String scope) {
        String key = versionKey(scope);
        String version = master.opsForValue().get(key);
        if (version != null) {
            return Long.parseLong(version);
        }
        master.opsForValue().setIfAbsent(key, String.valueOf(clock.millis()));
        return Long.parseLong(master.opsForValue().get(key));
    }

    // commit 뒤에 불리므로 어떤 실패도 쓰기 결과로 새어 나가지 않게 한다
    private void increaseVersion(String scope) {
        String key = versionKey(scope);
        try {
            master.opsForValue().setIfAbsent(key, String.valueOf(clock.millis()));
            master.opsForValue().increment(key);
        } catch (RuntimeException e) {
            pendingScopes.add(scope);
            log.warn("상품 목록 캐시 버전을 올리지 못해 다시 시도함. scope={}", scope, e);
        }
    }

    boolean hasPendingInvalidation(Long brandId) {
        return pendingScopes.contains(scopeOf(brandId));
    }

    static String scopeOf(Long brandId) {
        return brandId == null ? ALL : "brand=" + brandId;
    }

    static String versionKey(String scope) {
        return "product-list:version:" + scope;
    }

    static String dataKey(String scope, long version, ProductSort sort) {
        return "product-list:v1:" + scope + ":ver=" + version + ":sort=" + sort.name();
    }

    private static CachedPage toCached(Page<ProductView> page) {
        return new CachedPage(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }

    private static Page<ProductView> toPage(CachedPage cached) {
        return new PageImpl<>(cached.content(), PageRequest.of(cached.page(), cached.size()), cached.totalElements());
    }
}

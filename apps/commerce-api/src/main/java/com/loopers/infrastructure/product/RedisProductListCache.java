package com.loopers.infrastructure.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.config.cache.ProductCacheRedisConfig;
import com.loopers.domain.product.CachedProductItem;
import com.loopers.domain.product.ProductListCache;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * 브랜드 필터 + 가격순 목록을 페이지별 JSON 문자열로 Redis 에 보관한다.
 * TTL 은 저장 시에만 걸고 읽을 때 연장하지 않는다.
 */
@Slf4j
@Component
public class RedisProductListCache implements ProductListCache {

    private static final String KEY_FORMAT = "products:v1:brand=%d:sort=price_asc:page=%d:size=20";
    private static final int CACHED_PAGE_COUNT = 3;
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final TypeReference<List<CachedProductItem>> ITEM_LIST = new TypeReference<>() { };

    private static final String CACHE_METRIC = "product.list.cache";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public RedisProductListCache(
        @Qualifier(ProductCacheRedisConfig.PRODUCT_CACHE_REDIS_TEMPLATE) RedisTemplate<String, String> redisTemplate,
        ObjectMapper objectMapper,
        MeterRegistry meterRegistry
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Optional<List<CachedProductItem>> find(Long brandId, int page) {
        String key = key(brandId, page);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, ITEM_LIST));
        } catch (DataAccessException e) {
            countError("find_error");
            log.warn("상품 목록 캐시 조회 실패, DB 로 우회한다. key={}", key, e);
            return Optional.empty();
        } catch (JsonProcessingException e) {
            countError("find_error");
            log.warn("상품 목록 캐시 역직렬화 실패, DB 로 우회한다. key={}", key, e);
            return Optional.empty();
        }
    }

    @Override
    public void save(Long brandId, int page, List<CachedProductItem> items) {
        String key = key(brandId, page);
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(items), TTL);
        } catch (DataAccessException e) {
            countError("save_error");
            log.warn("상품 목록 캐시 저장 실패, 이번 응답은 캐시 없이 반환한다. key={}", key, e);
        } catch (JsonProcessingException e) {
            countError("save_error");
            log.warn("상품 목록 캐시 직렬화 실패, 이번 응답은 캐시 없이 반환한다. key={}", key, e);
        }
    }

    private void countError(String result) {
        meterRegistry.counter(CACHE_METRIC, "result", result).increment();
    }

    /**
     * 트랜잭션 밖에서 호출하면 registerSynchronization 이 IllegalStateException 을 던진다. 개발 실수를 드러내기 위해 잡지 않는다.
     */
    @Override
    public void evictBrandAfterCommit(Long brandId) {
        List<String> keys = IntStream.range(0, CACHED_PAGE_COUNT)
            .mapToObj(page -> key(brandId, page))
            .toList();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    redisTemplate.delete(keys);
                } catch (DataAccessException e) {
                    countError("evict_error");
                    log.warn("상품 목록 캐시 삭제 실패, TTL 만료까지 이전 목록이 남을 수 있다. keys={}", keys, e);
                }
            }
        });
    }

    private String key(Long brandId, int page) {
        return String.format(KEY_FORMAT, brandId, page);
    }
}

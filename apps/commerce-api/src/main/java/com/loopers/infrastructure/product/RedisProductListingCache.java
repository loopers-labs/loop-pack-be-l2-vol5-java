package com.loopers.infrastructure.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.common.PageWindow;
import com.loopers.domain.product.ProductListingCache;
import com.loopers.domain.product.ProductViewQuery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

@Slf4j
@Component
class RedisProductListingCache implements ProductListingCache {

    private static final String PREFIX = "product:list:v1:";
    private static final Duration TTL = Duration.ofSeconds(60);
    private static final Duration SKIP_AFTER_FAILURE = Duration.ofSeconds(5);
    private static final TypeReference<PageWindow<ProductViewQuery.Listing>> PAGE = new TypeReference<>() {};

    private final RedisTemplate<String, String> redis;
    private final ObjectMapper objectMapper;
    private final AtomicLong skipUntilNanos = new AtomicLong(System.nanoTime());

    RedisProductListingCache(
        @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis,
        ObjectMapper objectMapper
    ) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<PageWindow<ProductViewQuery.Listing>> find(Long brandId, ProductViewQuery.Sort sort) {
        if (skipping()) {
            return Optional.empty();
        }
        try {
            String json = redis.opsForValue().get(pageKey(brandId, sort));
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, PAGE));
        } catch (DataAccessException e) {
            skipAfter(e);
            return Optional.empty();
        } catch (JsonProcessingException e) {
            log.warn("product listing cache read failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void save(Long brandId, ProductViewQuery.Sort sort, PageWindow<ProductViewQuery.Listing> page) {
        if (skipping()) {
            return;
        }
        try {
            redis.opsForValue().set(pageKey(brandId, sort), objectMapper.writeValueAsString(page), TTL);
        } catch (DataAccessException e) {
            skipAfter(e);
        } catch (JsonProcessingException e) {
            log.warn("product listing cache write failed: {}", e.getMessage());
        }
    }

    @Override
    public boolean invalidate(Long brandId) {
        if (skipping()) {
            return false;
        }
        try {
            redis.delete(Stream.of(ProductViewQuery.Sort.values())
                .flatMap(sort -> Stream.of(pageKey(brandId, sort), pageKey(null, sort)))
                .toList());
            return true;
        } catch (DataAccessException e) {
            skipAfter(e);
            return false;
        }
    }

    private boolean skipping() {
        return System.nanoTime() - skipUntilNanos.get() < 0;
    }

    private void skipAfter(DataAccessException e) {
        skipUntilNanos.set(System.nanoTime() + SKIP_AFTER_FAILURE.toNanos());
        log.warn("product listing cache unavailable, reading the source for {}s: {}", SKIP_AFTER_FAILURE.toSeconds(), e.getMessage());
    }

    private static String pageKey(Long brandId, ProductViewQuery.Sort sort) {
        return PREFIX + "brand=" + scope(brandId) + ":sort=" + sort;
    }

    private static String scope(Long brandId) {
        return brandId == null ? "all" : String.valueOf(brandId);
    }
}

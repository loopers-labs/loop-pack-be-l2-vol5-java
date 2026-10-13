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
import java.util.OptionalLong;

@Slf4j
@Component
class RedisProductListingCache implements ProductListingCache {

    private static final String PREFIX = "product:list:v1:";
    private static final Duration TTL = Duration.ofSeconds(60);
    private static final TypeReference<PageWindow<ProductViewQuery.Listing>> PAGE = new TypeReference<>() {};

    private final RedisTemplate<String, String> redis;
    private final ObjectMapper objectMapper;

    RedisProductListingCache(
        @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis,
        ObjectMapper objectMapper
    ) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public OptionalLong generation(Long brandId) {
        try {
            String value = redis.opsForValue().get(generationKey(brandId));
            return OptionalLong.of(value == null ? 0L : Long.parseLong(value));
        } catch (DataAccessException e) {
            log.warn("product listing cache generation unavailable: {}", e.getMessage());
            return OptionalLong.empty();
        }
    }

    @Override
    public Optional<PageWindow<ProductViewQuery.Listing>> find(Long brandId, ProductViewQuery.Sort sort, long generation) {
        try {
            String json = redis.opsForValue().get(pageKey(brandId, sort, generation));
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, PAGE));
        } catch (DataAccessException | JsonProcessingException e) {
            log.warn("product listing cache read failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void save(Long brandId, ProductViewQuery.Sort sort, long generation, PageWindow<ProductViewQuery.Listing> page) {
        try {
            redis.opsForValue().set(pageKey(brandId, sort, generation), objectMapper.writeValueAsString(page), TTL);
        } catch (DataAccessException | JsonProcessingException e) {
            log.warn("product listing cache write failed: {}", e.getMessage());
        }
    }

    @Override
    public void invalidate(Long brandId) {
        try {
            redis.opsForValue().increment(generationKey(brandId));
            redis.opsForValue().increment(generationKey(null));
        } catch (DataAccessException e) {
            log.warn("product listing cache invalidation failed for brand {}: {}", brandId, e.getMessage());
        }
    }

    private static String generationKey(Long brandId) {
        return PREFIX + "gen:brand=" + scope(brandId);
    }

    private static String pageKey(Long brandId, ProductViewQuery.Sort sort, long generation) {
        return PREFIX + "brand=" + scope(brandId) + ":sort=" + sort + ":gen=" + generation;
    }

    private static String scope(Long brandId) {
        return brandId == null ? "all" : String.valueOf(brandId);
    }
}

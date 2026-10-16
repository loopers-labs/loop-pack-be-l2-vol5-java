package com.loopers.infrastructure.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.product.CustomerProductInfo;
import com.loopers.application.product.ProductListCache;
import com.loopers.application.product.ProductListCacheKey;
import com.loopers.config.redis.RedisConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Component
public class RedisProductListCache implements ProductListCache {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final String VERSION_KEY_PREFIX = "product:list:version:";
    private static final String ALL_BRANDS = "all";
    private static final TypeReference<List<CustomerProductInfo>> PAYLOAD_TYPE = new TypeReference<>() {
    };

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    // 무효화 직후 복제 지연으로 이전 값을 읽지 않도록 master에서 읽고 쓴다.
    public RedisProductListCache(
        @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redisTemplate,
        ObjectMapper objectMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public long currentVersion(Long brandId) {
        String version = redisTemplate.opsForValue().get(versionKey(brandId == null ? ALL_BRANDS : brandId.toString()));
        return version == null ? 0L : Long.parseLong(version);
    }

    @Override
    public Optional<List<CustomerProductInfo>> find(ProductListCacheKey key) {
        String payload = redisTemplate.opsForValue().get(key.value());
        if (payload == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payload, PAYLOAD_TYPE));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("상품 목록 캐시를 읽을 수 없습니다.", exception);
        }
    }

    @Override
    public void put(ProductListCacheKey key, List<CustomerProductInfo> products) {
        try {
            redisTemplate.opsForValue().set(key.value(), objectMapper.writeValueAsString(products), TTL);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("상품 목록 캐시를 저장할 수 없습니다.", exception);
        }
    }

    @Override
    public void invalidate(Long brandId) {
        redisTemplate.opsForValue().increment(versionKey(brandId.toString()));
        redisTemplate.opsForValue().increment(versionKey(ALL_BRANDS));
    }

    private String versionKey(String scope) {
        return VERSION_KEY_PREFIX + scope;
    }
}

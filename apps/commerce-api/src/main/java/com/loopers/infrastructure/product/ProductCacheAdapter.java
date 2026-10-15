package com.loopers.infrastructure.product;

import com.loopers.application.product.ProductCachePort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

@RequiredArgsConstructor
@Component
public class ProductCacheAdapter implements ProductCachePort {

    private static final String CACHE_NAME = "products";

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public void evictByBrandId(Long brandId) {
        String brandKey = brandId != null ? brandId.toString() : "all";
        deleteByPattern(CACHE_NAME + "::" + brandKey + ":*");
        if (brandId != null) {
            deleteByPattern(CACHE_NAME + "::all:*");
        }
    }

    private void deleteByPattern(String pattern) {
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception ignored) {
        }
    }
}

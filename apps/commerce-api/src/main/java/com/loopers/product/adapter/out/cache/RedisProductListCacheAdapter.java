package com.loopers.product.adapter.out.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.product.application.port.out.ProductListCachePort;
import com.loopers.product.application.port.out.ProductListKey;
import com.loopers.product.application.port.out.ProductListPage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Redis 목록 캐시. 값은 JSON 문자열, 키는 "product:list:v1:" + 조회 조건이다.
 * v1은 직렬화 형식의 버전이다 — 형식을 바꾸면 올려서 배포 전후의 값이 섞이지 않게 한다 (DB 변경을 감지하는 용도가 아니다).
 */
@Component
public class RedisProductListCacheAdapter implements ProductListCachePort {

    private static final String KEY_PREFIX = "product:list:v1:";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RedisProductListCacheAdapter(
        RedisTemplate<String, String> redisTemplate,
        ObjectMapper objectMapper,
        @Value("${product.list-cache.ttl:60s}") Duration ttl
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    @Override
    public Optional<ProductListPage> find(ProductListKey key) {
        String json = redisTemplate.opsForValue().get(keyOf(key));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, ProductListPage.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("상품 목록 캐시를 읽을 수 없습니다.", e);
        }
    }

    @Override
    public void save(ProductListKey key, ProductListPage page) {
        try {
            redisTemplate.opsForValue().set(keyOf(key), objectMapper.writeValueAsString(page), ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("상품 목록 캐시를 쓸 수 없습니다.", e);
        }
    }

    @Override
    public void invalidateAll() {
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(1_000).build())) {
            cursor.forEachRemaining(keys::add);
        }
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private static String keyOf(ProductListKey key) {
        return KEY_PREFIX
            + "brand=" + (key.brandId() == null ? "all" : key.brandId())
            + ":sort=" + key.sort()
            + ":page=" + key.page()
            + ":size=" + key.size();
    }
}

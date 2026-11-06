package com.loopers.infrastructure.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.product.ProductIdPage;
import com.loopers.application.product.ProductListCache;
import com.loopers.application.product.ProductListCacheKey;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

// 키에 revision이 들어가서 한 키의 값은 바뀌지 않는다. 그래서 replica에서 읽어도 오래된 값이 아니라 miss만 생긴다
@Slf4j
@RequiredArgsConstructor
@Component
public class RedisProductListCache implements ProductListCache {

    static final String METRIC = "product.list.cache";

    // v2는 값의 직렬화 형식 버전이다. 값이 상품 정보 전체에서 상품 id 목록으로 바뀌어 v1에서 올렸다
    private static final String KEY_PREFIX = "product:list:v2";

    // revision이 바뀌어 더 읽히지 않는 키를 정리하는 수명이다. 신선도는 revision이 맡는다
    private static final Duration TTL = Duration.ofMinutes(10);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Override
    public Optional<ProductIdPage> find(ProductListCacheKey key) {
        String value;
        try {
            value = redisTemplate.opsForValue().get(toRedisKey(key));
        } catch (DataAccessException e) {
            count("get", "error");
            log.warn("상품 목록 캐시 조회 실패, DB에서 조회한다: {}", e.getMessage());
            return Optional.empty();
        }
        if (value == null) {
            count("get", "miss");
            return Optional.empty();
        }
        try {
            ProductIdPage page = objectMapper.readValue(value, ProductIdPage.class);
            count("get", "hit");
            return Optional.of(page);
        } catch (JsonProcessingException e) {
            count("get", "error");
            log.warn("상품 목록 캐시 값을 읽지 못해 DB에서 조회한다: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void save(ProductListCacheKey key, ProductIdPage page) {
        try {
            redisTemplate.opsForValue().set(toRedisKey(key), objectMapper.writeValueAsString(page), TTL);
            count("put", "ok");
        } catch (DataAccessException | JsonProcessingException e) {
            count("put", "error");
            log.warn("상품 목록 캐시 저장 실패, 조회 결과는 그대로 응답한다: {}", e.getMessage());
        }
    }

    static String toRedisKey(ProductListCacheKey key) {
        String brand = key.brandId() == null ? "all" : String.valueOf(key.brandId());
        return KEY_PREFIX + ":brand=" + brand + ":sort=" + key.sortType() + ":rev=" + key.revision()
            + ":page=" + key.page() + ":size=" + key.size();
    }

    private void count(String operation, String result) {
        meterRegistry.counter(METRIC, "op", operation, "result", result).increment();
    }
}

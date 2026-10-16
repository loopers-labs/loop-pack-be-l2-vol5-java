package com.loopers.infrastructure.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.product.ProductDetailCache;
import com.loopers.domain.product.ProductDetailData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 상세 기본정보를 상품별 JSON 문자열 하나로 master Redis 에 저장한다.
 * 저장은 SET EX 로 고정 TTL 을 정하고, 조회는 GET 만 사용해 hit 가 만료 시각을 늘리지 않는다.
 */
@Slf4j
@Component
public class ProductDetailRedisCache implements ProductDetailCache {

    public static final String KEY_PREFIX = "commerce:product-detail:v1:";
    /** 한 번의 DEL 에 넣는 key 수. 브랜드 단위 무효화가 하나의 큰 명령이 되지 않게 나눈다. */
    private static final int EVICT_CHUNK_SIZE = 500;

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Duration ttl;

    public ProductDetailRedisCache(
        @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redisTemplate,
        ObjectMapper objectMapper,
        @Value("${commerce.cache.product-detail.enabled}") boolean enabled,
        @Value("${commerce.cache.product-detail.ttl}") Duration ttl
    ) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("상품 상세 기본정보 캐시 TTL 은 양수여야 합니다. [ttl = " + ttl + "]");
        }
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.ttl = ttl;
    }

    public static String keyOf(Long productId) {
        return KEY_PREFIX + productId;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public Optional<ProductDetailData.CacheValue> find(Long productId) {
        String json;
        try {
            json = redisTemplate.opsForValue().get(keyOf(productId));
        } catch (DataAccessException e) {
            log.warn("상품 상세 기본정보 캐시를 읽지 못해 DB 원본으로 조회합니다. [productId = {}]", productId, e);
            return Optional.empty();
        }
        if (json == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(parse(json));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("상품 상세 기본정보 캐시의 형식이 올바르지 않아 miss 로 처리하고 DB 원본으로 다시 채웁니다. [productId = {}]",
                productId, e);
            return Optional.empty();
        }
    }

    @Override
    public void put(Long productId, ProductDetailData.CacheValue cacheValue) {
        try {
            String json = objectMapper.writeValueAsString(Payload.from(cacheValue));
            redisTemplate.opsForValue().set(keyOf(productId), json, ttl);
        } catch (JsonProcessingException | DataAccessException e) {
            log.warn("상품 상세 기본정보 캐시를 채우지 못했습니다. 응답은 DB 원본으로 유지합니다. [productId = {}]", productId, e);
        }
    }

    @Override
    public void evictAll(Collection<Long> productIds) {
        List<String> keys = productIds.stream().distinct().map(ProductDetailRedisCache::keyOf).toList();
        for (int from = 0; from < keys.size(); from += EVICT_CHUNK_SIZE) {
            redisTemplate.delete(keys.subList(from, Math.min(from + EVICT_CHUNK_SIZE, keys.size())));
        }
    }

    /** JSON 리터럴 null 은 Jackson 이 null 로 돌려주므로 값 누락과 같은 형식 오류로 본다. */
    private ProductDetailData.CacheValue parse(String json) throws JsonProcessingException {
        Payload payload = objectMapper.readValue(json, Payload.class);
        if (payload == null) {
            throw new IllegalArgumentException("상품 상세 기본정보 캐시 값이 JSON null 입니다.");
        }
        return payload.toCacheValue();
    }

    /** 저장 형식. 누락된 값은 형식 오류로 본다. */
    record Payload(String name, String brandName, Long price, Long stockQuantity) {

        static Payload from(ProductDetailData.CacheValue cacheValue) {
            return new Payload(cacheValue.name(), cacheValue.brandName(), cacheValue.price(), cacheValue.stockQuantity());
        }

        ProductDetailData.CacheValue toCacheValue() {
            if (name == null || brandName == null || price == null || stockQuantity == null) {
                throw new IllegalArgumentException("상품 상세 기본정보 캐시에 필요한 값이 없습니다.");
            }
            return new ProductDetailData.CacheValue(name, brandName, price, stockQuantity);
        }
    }
}

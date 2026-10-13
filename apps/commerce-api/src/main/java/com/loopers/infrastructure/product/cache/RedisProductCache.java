package com.loopers.infrastructure.product.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.product.query.ProductCache;
import com.loopers.application.product.query.ProductCacheValue;
import com.loopers.config.redis.RedisConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * {@link ProductCache} 의 Redis 구현 (DR-37). 값은 JSON 문자열이다.
 * 읽기는 기본 템플릿(replica 우선), 쓰기는 master 템플릿을 쓴다. 키가 revision 마다 불변이라 replica 지연은 miss 로만 나타난다.
 * Redis 장애·역직렬화 실패는 던지지 않고 읽기는 miss, 쓰기는 무시로 돌린다. 대기 상한은 datasource.redis.timeout 이다.
 */
@Slf4j
@Component
public class RedisProductCache implements ProductCache {
    static final Duration DETAIL_TTL = Duration.ofMinutes(30);
    private static final String SCHEMA_VERSION = "v1";

    private final RedisTemplate<String, String> readTemplate;
    private final RedisTemplate<String, String> writeTemplate;
    private final ObjectMapper objectMapper;

    public RedisProductCache(
        RedisTemplate<String, String> readTemplate,
        @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> writeTemplate,
        ObjectMapper objectMapper
    ) {
        this.readTemplate = readTemplate;
        this.writeTemplate = writeTemplate;
        this.objectMapper = objectMapper;
    }

    /** product:detail:{스키마 버전}:{상품 id}:{상품 revision}:{브랜드 revision} */
    static String detailKey(Long productId, long productRevision, long brandRevision) {
        return "product:detail:" + SCHEMA_VERSION + ":" + productId + ":" + productRevision + ":" + brandRevision;
    }

    @Override
    public Optional<ProductCacheValue.Item> findDetail(Long productId, long productRevision, long brandRevision) {
        return get(detailKey(productId, productRevision, brandRevision), ProductCacheValue.Item.class);
    }

    @Override
    public void putDetail(Long productId, long productRevision, long brandRevision, ProductCacheValue.Item item) {
        put(detailKey(productId, productRevision, brandRevision), item, DETAIL_TTL);
    }

    private <T> Optional<T> get(String key, Class<T> type) {
        String json;
        try {
            json = readTemplate.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("상품 캐시 읽기 실패, DB 로 읽는다. [key = {}]", key, e);
            return Optional.empty();
        }
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, type));
        } catch (JsonProcessingException e) {
            log.warn("상품 캐시 값을 읽을 수 없어 miss 로 본다. [key = {}]", key, e);
            return Optional.empty();
        }
    }

    private void put(String key, Object value, Duration ttl) {
        try {
            writeTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("상품 캐시 쓰기 실패, 무시한다. [key = {}]", key, e);
        }
    }
}

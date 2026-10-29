package com.loopers.infrastructure.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.product.ProductListItem;
import com.loopers.application.product.ProductListQuery;
import com.loopers.application.product.port.ProductListCache;
import com.loopers.config.redis.RedisConfig;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

// 설계 19절. 버전 키 하나로 모든 목록 키를 무효화한다. 복제 지연 중 옛 버전을 읽지 않도록 모든 명령을 master로 보낸다.
// Redis 실패·타임아웃은 요청을 실패시키지 않고 캐시 미사용(DB 조회)으로 이어지게 하며, 원인을 로그로 남긴다.
@Slf4j
@Component
public class RedisProductListCache implements ProductListCache {
    private static final String VERSION_KEY = "products:list:version";
    private static final TypeReference<List<ProductListItem>> ITEMS = new TypeReference<>() { };

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RedisProductListCache(@Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redisTemplate,
        ObjectMapper objectMapper, @Value("${commerce.product-list-cache.ttl}") Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    @Override
    public OptionalLong currentVersion() {
        try {
            String version = redisTemplate.opsForValue().get(VERSION_KEY);
            return OptionalLong.of(version == null ? 0 : Long.parseLong(version));
        } catch (DataAccessException | NumberFormatException e) {
            log.warn("상품 목록 캐시 버전을 읽지 못해 DB로 조회합니다.", e);
            return OptionalLong.empty();
        }
    }

    @Override
    public Optional<List<ProductListItem>> find(long version, ProductListQuery query) {
        String key = key(version, query);
        try {
            String cached = redisTemplate.opsForValue().get(key);
            return cached == null ? Optional.empty() : Optional.of(objectMapper.readValue(cached, ITEMS));
        } catch (DataAccessException | JsonProcessingException e) {
            log.warn("상품 목록 캐시를 읽지 못해 DB로 조회합니다. key={}", key, e);
            return Optional.empty();
        }
    }

    @Override
    public void save(long version, ProductListQuery query, List<ProductListItem> items) {
        String key = key(version, query);
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(items), ttl);
        } catch (DataAccessException | JsonProcessingException e) {
            log.warn("상품 목록 캐시를 저장하지 못했습니다. key={}", key, e);
        }
    }

    @Override
    public void invalidate() {
        try {
            redisTemplate.opsForValue().increment(VERSION_KEY);
        } catch (DataAccessException e) {
            // 실패하면 낡은 목록이 최대 TTL 동안 남는다(TTL이 안전망).
            log.error("상품 목록 캐시 버전을 올리지 못했습니다. 최대 {} 동안 낡은 목록이 보일 수 있습니다.", ttl, e);
        }
    }

    private String key(long version, ProductListQuery query) {
        String brand = query.brandId() == null ? "all" : String.valueOf(query.brandId());
        return "products:list:v" + version + ":brand=" + brand + ":sort=" + query.sort()
            + ":page=" + query.page() + ":size=" + query.size();
    }
}

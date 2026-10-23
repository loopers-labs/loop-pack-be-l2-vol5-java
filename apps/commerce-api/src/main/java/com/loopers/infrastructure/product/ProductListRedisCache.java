package com.loopers.infrastructure.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.product.cache.ProductListCache;
import com.loopers.application.product.dto.ProductListQuery;
import com.loopers.application.product.cache.CachedProductListInfo;
import com.loopers.config.redis.RedisConfig;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 상품 목록 캐시의 Redis 구현. 키 형식·JSON 직렬화·TTL·영역 비우기와 Redis 오류 처리를 여기서 숨긴다.
 *
 * <p>무효화 직후 replica 의 옛 값을 읽지 않도록 master 로 읽고 쓴다. 잡는 예외는 Redis 접근
 * ({@link DataAccessException})과 JSON 변환({@link JsonProcessingException})뿐이며, 오류마다 로그와
 * {@value #ERROR_METRIC} 카운터를 남긴다. 정상 miss 는 오류로 세지 않는다.
 */
@Slf4j
@Component
public class ProductListRedisCache implements ProductListCache {

    static final String KEY_PREFIX = "product:list:";
    // payload 형식 버전. 직렬화 계약을 바꾸면 올린다(DB 변경 감지 수단이 아니다).
    private static final String FORMAT_VERSION = "v1";
    private static final String ERROR_METRIC = "product.list.cache.errors";
    private static final int SCAN_COUNT = 500;

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Duration ttl;

    public ProductListRedisCache(
        @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redisTemplate,
        ObjectMapper objectMapper,
        MeterRegistry meterRegistry,
        @Value("${product.list-cache.ttl}") Duration ttl
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.ttl = ttl;
    }

    @Override
    public Optional<CachedProductListInfo> find(ProductListQuery query) {
        String key = keyOf(query);
        try {
            String payload = redisTemplate.opsForValue().get(key);
            if (payload == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(payload, CachedProductListInfo.class));
        } catch (DataAccessException | JsonProcessingException e) {
            recordError("find", key, e);
            return Optional.empty();
        }
    }

    @Override
    public void save(ProductListQuery query, CachedProductListInfo snapshot) {
        String key = keyOf(query);
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(snapshot), ttl);
        } catch (DataAccessException | JsonProcessingException e) {
            recordError("save", key, e);
        }
    }

    // KEYS 는 Redis 를 막으므로 SCAN 으로 나눠 찾고, 큰 값의 해제가 Redis 를 막지 않도록 UNLINK 로 지운다.
    @Override
    public void evictAll() {
        ScanOptions options = ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(SCAN_COUNT).build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            List<String> batch = new ArrayList<>(SCAN_COUNT);
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() == SCAN_COUNT) {
                    redisTemplate.unlink(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                redisTemplate.unlink(batch);
            }
        } catch (DataAccessException e) {
            recordError("evict", KEY_PREFIX + "*", e);
        }
    }

    static String keyOf(ProductListQuery query) {
        String brand = query.brandId() == null ? "all" : String.valueOf(query.brandId());
        return KEY_PREFIX + FORMAT_VERSION
            + ":brand=" + brand
            + ":sort=" + query.sort().value()
            + ":page=" + query.page()
            + ":size=" + query.size();
    }

    private void recordError(String operation, String key, Exception e) {
        meterRegistry.counter(ERROR_METRIC, "operation", operation).increment();
        log.warn("상품 목록 캐시 {} 실패. 캐시 없이 진행합니다. key={}", operation, key, e);
    }
}

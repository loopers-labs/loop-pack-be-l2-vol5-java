package com.loopers.product.adapter.out.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.config.redis.RedisConfig;
import com.loopers.product.application.port.out.ProductListCachePort;
import com.loopers.product.application.port.out.ProductListKey;
import com.loopers.product.application.port.out.ProductListPage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis 목록 캐시. 값은 JSON 문자열이다.
 * <ul>
 *   <li>세대 키: {@code product:list:v1:generation} — 숫자 하나, TTL 없음.</li>
 *   <li>목록 키: {@code product:list:v1:g{세대}:brand=…:sort=…:page=…:size=…} — TTL {@code product.list-cache.ttl} (기본 60초).</li>
 * </ul>
 * v1은 직렬화 형식의 버전이다 — 형식을 바꾸면 올려서 배포 전후의 값이 섞이지 않게 한다 (DB 변경을 감지하는 용도가 아니다).
 * <p>
 * 읽기·쓰기 모두 master 연결을 쓴다. 기본 연결(REPLICA_PREFERRED)로 세대를 읽으면, 쓰기 직후 복제가 늦은 replica에서
 * 옛 세대를 받아 옛 목록을 돌려줄 수 있다. 세대는 "지금 무엇이 최신인가"를 정하는 값이라 권위 있는 노드에서 읽는다.
 */
@Component
public class RedisProductListCacheAdapter implements ProductListCachePort {

    private static final String KEY_PREFIX = "product:list:v1:";
    private static final String GENERATION_KEY = KEY_PREFIX + "generation";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RedisProductListCacheAdapter(
        @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redisTemplate,
        ObjectMapper objectMapper,
        @Value("${product.list-cache.ttl:60s}") Duration ttl
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    /**
     * 세대 키가 없으면(첫 사용, Redis 재시작·축출) 현재 시각(ms)으로 시작한다. 1부터 다시 세면, 아직 TTL이 남은 옛 세대의
     * 키와 번호가 겹칠 수 있어서다. 쓰기(invalidateAll)도 같은 규칙으로 시작한다.
     * 전제: 세대가 1 ms에 1번보다 빠르게 오르지 않는다. 그보다 빠르면, 세대 키만 축출된 뒤 시각으로 다시 시작한 값이
     * TTL이 남은 옛 세대와 겹칠 수 있다 (Redis 전체가 비워지는 재시작에서는 겹칠 옛 키도 없다).
     */
    @Override
    public long currentGeneration() {
        String generation = redisTemplate.opsForValue().get(GENERATION_KEY);
        if (generation == null) {
            startGenerationIfAbsent();
            generation = redisTemplate.opsForValue().get(GENERATION_KEY);
        }
        return Long.parseLong(generation);
    }

    @Override
    public Optional<ProductListPage> find(long generation, ProductListKey key) {
        String json = redisTemplate.opsForValue().get(keyOf(generation, key));
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
    public void save(long generation, ProductListKey key, ProductListPage page) {
        try {
            redisTemplate.opsForValue().set(keyOf(generation, key), objectMapper.writeValueAsString(page), ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("상품 목록 캐시를 쓸 수 없습니다.", e);
        }
    }

    @Override
    public void invalidateAll() {
        // 키가 없을 때 INCR만 하면 1부터 다시 센다 — 먼저 시각으로 시작해 둔다
        startGenerationIfAbsent();
        redisTemplate.opsForValue().increment(GENERATION_KEY);
    }

    private void startGenerationIfAbsent() {
        redisTemplate.opsForValue().setIfAbsent(GENERATION_KEY, String.valueOf(System.currentTimeMillis()));
    }

    private static String keyOf(long generation, ProductListKey key) {
        return KEY_PREFIX
            + "g" + generation
            + ":brand=" + (key.brandId() == null ? "all" : key.brandId())
            + ":sort=" + key.sort()
            + ":page=" + key.page()
            + ":size=" + key.size();
    }
}

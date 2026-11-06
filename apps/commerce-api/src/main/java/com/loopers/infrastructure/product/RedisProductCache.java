package com.loopers.infrastructure.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.product.ProductCache;
import com.loopers.application.product.ProductSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 키에 상품 revision이 들어가서 한 키의 값은 바뀌지 않는다. 여러 상품은 MGET 한 번으로 읽는다
@Slf4j
@RequiredArgsConstructor
@Component
public class RedisProductCache implements ProductCache {

    static final String METRIC = "product.cache";

    private static final String KEY_PREFIX = "product:detail:v1";

    // revision이 바뀌어 더 읽히지 않는 키를 정리하는 수명이다. 신선도는 revision이 맡는다
    private static final Duration TTL = Duration.ofMinutes(10);

    // KEYS: 상품 캐시 키들, ARGV[1]: TTL(ms), ARGV[2..]: KEYS와 같은 순서의 값
    private static final RedisScript<Long> SET_ALL_WITH_TTL = RedisScript.of(
        "for i, key in ipairs(KEYS) do redis.call('SET', key, ARGV[i + 1], 'PX', ARGV[1]) end return #KEYS",
        Long.class
    );

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Override
    public Map<Long, ProductSnapshot> findAll(Map<Long, Long> revisionsByProductId) {
        if (revisionsByProductId.isEmpty()) {
            return Map.of();
        }
        List<Long> productIds = new ArrayList<>(revisionsByProductId.keySet());
        List<String> keys = productIds.stream().map(id -> toRedisKey(id, revisionsByProductId.get(id))).toList();
        List<String> values;
        try {
            values = redisTemplate.opsForValue().multiGet(keys);
        } catch (DataAccessException e) {
            count("get", "error", productIds.size());
            log.warn("상품 캐시 조회 실패, DB에서 조회한다: {}", e.getMessage());
            return Map.of();
        }

        Map<Long, ProductSnapshot> found = new HashMap<>();
        for (int i = 0; i < productIds.size(); i++) {
            String value = values == null ? null : values.get(i);
            if (value == null) {
                count("get", "miss", 1);
                continue;
            }
            try {
                found.put(productIds.get(i), objectMapper.readValue(value, ProductSnapshot.class));
                count("get", "hit", 1);
            } catch (JsonProcessingException e) {
                count("get", "error", 1);
                log.warn("상품 캐시 값을 읽지 못해 DB에서 조회한다: {}", e.getMessage());
            }
        }
        return found;
    }

    // 여러 상품을 스크립트 한 번(왕복 한 번)으로 저장한다. 실습 환경에서 측정한 두 방식의 비용 때문이다:
    // - 상품마다 SET: miss 한 번에 Redis 왕복이 상품 수만큼 늘었다
    // - 파이프라인: 공유 연결을 쓰지 못해 실행마다 전용 master/replica 연결을 새로 맺어, miss 한 번에 연결이 약 5개 더 생겼다
    @Override
    public void saveAll(Map<Long, Long> revisionsByProductId, List<ProductSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }
        try {
            List<String> keys = new ArrayList<>();
            List<String> args = new ArrayList<>();
            args.add(String.valueOf(TTL.toMillis()));
            for (ProductSnapshot snapshot : snapshots) {
                keys.add(toRedisKey(snapshot.id(), revisionsByProductId.get(snapshot.id())));
                args.add(objectMapper.writeValueAsString(snapshot));
            }
            redisTemplate.execute(SET_ALL_WITH_TTL, keys, args.toArray());
            count("put", "ok", snapshots.size());
        } catch (DataAccessException | JsonProcessingException e) {
            count("put", "error", 1);
            log.warn("상품 캐시 저장 실패, 조회 결과는 그대로 응답한다: {}", e.getMessage());
        }
    }

    static String toRedisKey(Long productId, Long revision) {
        return KEY_PREFIX + ":id=" + productId + ":rev=" + revision;
    }

    private void count(String operation, String result, int amount) {
        meterRegistry.counter(METRIC, "op", operation, "result", result).increment(amount);
    }
}

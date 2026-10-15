package com.loopers.infrastructure.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.product.CacheResult;
import com.loopers.application.product.CachedBrand;
import com.loopers.application.product.CachedProduct;
import com.loopers.application.product.CachedProductIds;
import com.loopers.application.product.ProductCache;
import com.loopers.application.product.ProductListLookup;
import com.loopers.domain.product.ProductSort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * ProductCache의 Redis 구현 (docs/week4/design.md 6·8번 섹션).
 *
 * <p>키: 목록 product:list:v{버전}:{brandId|all}:{sort}:{page}:{size}, 상품 product:{id}, 브랜드 brand:{id},
 * 목록 버전 product:list:version(TTL 없음, 없으면 0). 값은 JSON.
 * 모든 Redis 호출은 실패를 삼키고 WARN 로그 + unavailable/false로 알린다 — 재시도하지 않는다(8번 섹션).
 * 로그는 키와 예외 메시지만 남긴다 — Redis가 멈춘 동안에는 요청마다 실패가 나므로 스택트레이스를 찍으면 로그가 묻힌다.
 * 캐시 전용 연결(명령 타임아웃 50ms, 끊기면 즉시 거절)만 쓴다.
 */
@Slf4j
@Component
public class RedisProductCache implements ProductCache {

    /**
     * 60초: 좋아요 수의 반영 지연 한도이자, 무효화 실패·늦은 채움 때 틀린 재고·가격이 보일 수 있는 최대 시간.
     * 30초도 검토했지만 재고·가격은 평소 즉시 무효화되어 TTL과 무관하고, 짧을수록 적중률이 떨어진다 (6번 섹션 "TTL 60초").
     */
    static final long TTL_SECONDS = 60;
    static final String LIST_VERSION_KEY = "product:list:version";
    // 브랜드 삭제처럼 지울 상품 키가 많을 때 DEL 하나에 담는 최대 키 수 (7번 섹션 "DEL을 묶어서 보낸다").
    private static final int DELETE_CHUNK_SIZE = 500;
    // KEYS[i]에 ARGV[i]를 저장하고, 마지막 ARGV를 TTL(초)로 쓴다.
    private static final RedisScript<Long> SET_ALL_WITH_TTL = new DefaultRedisScript<>(
        "local ttl = ARGV[#ARGV] "
            + "for i, key in ipairs(KEYS) do redis.call('SET', key, ARGV[i], 'EX', ttl) end "
            + "return #KEYS",
        Long.class
    );

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisProductCache(
        @Qualifier(CacheRedisConfig.CACHE_REDIS_TEMPLATE) RedisTemplate<String, String> redisTemplate,
        ObjectMapper objectMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public ProductListLookup lookupProductIds(Long brandId, ProductSort sort, int page, int size) {
        String key;
        try {
            key = listKey(readListVersion(), brandId, sort, page, size);
        } catch (RuntimeException e) {
            log.warn("캐시 조회 실패 [key = {}]: {}", LIST_VERSION_KEY, e.toString());
            return new ProductListLookup(null, CacheResult.unavailable());
        }
        String json;
        try {
            json = redisTemplate.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("캐시 조회 실패 [key = {}]: {}", key, e.toString());
            return new ProductListLookup(key, CacheResult.unavailable());
        }
        CachedProductIds value = json == null ? null : deserialize(key, json, CachedProductIds.class);
        return new ProductListLookup(key, value == null ? CacheResult.miss() : CacheResult.hit(value));
    }

    @Override
    public boolean putProductIds(ProductListLookup lookup, CachedProductIds value) {
        if (lookup == null || lookup.key() == null || value == null) {
            return false;
        }
        return setAll(Map.of(lookup.key(), value));
    }

    @Override
    public CacheResult<Map<Long, CachedProduct>> getProducts(Collection<Long> ids) {
        return multiGet(ids, RedisProductCache::productKey, CachedProduct.class);
    }

    @Override
    public boolean putProducts(Collection<CachedProduct> products) {
        Map<String, Object> entries = new LinkedHashMap<>();
        products.forEach(product -> entries.put(productKey(product.id()), product));
        return setAll(entries);
    }

    @Override
    public CacheResult<Map<Long, CachedBrand>> getBrands(Collection<Long> ids) {
        return multiGet(ids, RedisProductCache::brandKey, CachedBrand.class);
    }

    @Override
    public boolean putBrands(Collection<CachedBrand> brands) {
        Map<String, Object> entries = new LinkedHashMap<>();
        brands.forEach(brand -> entries.put(brandKey(brand.id()), brand));
        return setAll(entries);
    }

    @Override
    public void evictProducts(Collection<Long> ids) {
        List<String> keys = ids.stream().distinct().map(RedisProductCache::productKey).toList();
        for (int from = 0; from < keys.size(); from += DELETE_CHUNK_SIZE) {
            List<String> chunk = keys.subList(from, Math.min(from + DELETE_CHUNK_SIZE, keys.size()));
            try {
                redisTemplate.delete(chunk);
            } catch (RuntimeException e) {
                log.warn("캐시 무효화 실패 [keys = {}]: {}", chunk, e.toString());
            }
        }
    }

    @Override
    public void evictBrand(Long id) {
        String key = brandKey(id);
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException e) {
            log.warn("캐시 무효화 실패 [key = {}]: {}", key, e.toString());
        }
    }

    @Override
    public void bumpListVersion() {
        try {
            redisTemplate.opsForValue().increment(LIST_VERSION_KEY);
        } catch (RuntimeException e) {
            log.warn("캐시 무효화 실패 [key = {}]: {}", LIST_VERSION_KEY, e.toString());
        }
    }

    private long readListVersion() {
        String version = redisTemplate.opsForValue().get(LIST_VERSION_KEY);
        return version == null ? 0L : Long.parseLong(version);
    }

    private <T> CacheResult<Map<Long, T>> multiGet(Collection<Long> ids, Function<Long, String> toKey, Class<T> type) {
        List<Long> distinctIds = ids.stream().distinct().toList();
        if (distinctIds.isEmpty()) {
            return CacheResult.hit(Map.of());
        }
        List<String> keys = distinctIds.stream().map(toKey).toList();
        List<String> values;
        try {
            values = redisTemplate.opsForValue().multiGet(keys);
        } catch (RuntimeException e) {
            log.warn("캐시 조회 실패 [keys = {}]: {}", keys, e.toString());
            return CacheResult.unavailable();
        }
        if (values == null) {
            // 파이프라인·트랜잭션 안에서만 null이 오지만, 방어적으로 실패로 취급한다.
            log.warn("캐시 조회 실패 [keys = {}] (MGET 결과 없음)", keys);
            return CacheResult.unavailable();
        }
        Map<Long, T> found = new HashMap<>();
        for (int i = 0; i < distinctIds.size(); i++) {
            String json = values.get(i);
            if (json == null) {
                continue;
            }
            T value = deserialize(keys.get(i), json, type);
            if (value != null) {
                found.put(distinctIds.get(i), value);
            }
        }
        return CacheResult.hit(found);
    }

    // 여러 키를 Lua 스크립트 한 번(EVAL)으로 SET EX 한다 — 공유 연결로 왕복 1번. 직렬화에 실패한 항목은 건너뛴다.
    // 경위(docs/week4/measurements/after-cache-latency.txt):
    // 1) 파이프라인(executePipelined)은 Spring Data Redis에서 전용 연결이 필요해, 연결 풀이 없으면 호출마다 master·replica에
    //    새 연결을 맺었다 — miss 요청당 연결 약 15개, 측정 대상 miss p50 75ms(캐시 없음 27.8ms), 연속 요청에서 임시 포트 고갈.
    // 2) 키마다 SET EX를 차례로 보내면 연결 문제는 사라지지만 목록 miss 하나가 약 25번 왕복해 54ms였다(로컬 왕복 약 0.9ms).
    // 쓰기 명령이라 Lettuce가 master로 보낸다(읽기만 replica 우선).
    private boolean setAll(Map<String, ?> entries) {
        List<String> keys = new ArrayList<>();
        List<String> args = new ArrayList<>();
        entries.forEach((key, value) -> {
            String json = serialize(key, value);
            if (json != null) {
                keys.add(key);
                args.add(json);
            }
        });
        if (keys.isEmpty()) {
            return true;
        }
        args.add(String.valueOf(TTL_SECONDS));
        try {
            redisTemplate.execute(SET_ALL_WITH_TTL, keys, args.toArray());
            return true;
        } catch (RuntimeException e) {
            log.warn("캐시 저장 실패 [keys = {}]: {}", keys, e.toString());
            return false;
        }
    }

    private String serialize(String key, Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("캐시 값 직렬화 실패 [key = {}]: {}", key, e.toString());
            return null;
        }
    }

    // 역직렬화 실패(형식이 바뀐 옛 값 등)는 그 키만 miss로 취급한다 — DB에서 다시 읽어 덮어쓰게 된다.
    private <T> T deserialize(String key, String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.warn("캐시 값 역직렬화 실패, miss로 취급 [key = {}]: {}", key, e.toString());
            return null;
        }
    }

    static String listKey(long version, Long brandId, ProductSort sort, int page, int size) {
        String brand = brandId == null ? "all" : String.valueOf(brandId);
        return "product:list:v" + version + ":" + brand + ":" + sort.name().toLowerCase(Locale.ROOT) + ":" + page + ":" + size;
    }

    static String productKey(Long id) {
        return "product:" + id;
    }

    static String brandKey(Long id) {
        return "brand:" + id;
    }
}

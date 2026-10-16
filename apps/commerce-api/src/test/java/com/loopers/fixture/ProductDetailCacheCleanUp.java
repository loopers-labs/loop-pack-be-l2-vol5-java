package com.loopers.fixture;

import com.loopers.config.redis.RedisConfig;
import com.loopers.infrastructure.product.ProductDetailRedisCache;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 테스트가 만든 상품 상세 기본정보 key 만 지운다. DB 초기화로 상품 ID 가 다시 1 부터 시작하므로
 * 이전 테스트의 기본정보가 다음 테스트의 같은 ID 에 섞이지 않게 한다. 저장소 전체를 비우지 않는다.
 */
@Component
public class ProductDetailCacheCleanUp {

    private final RedisTemplate<String, String> redisTemplate;

    public ProductDetailCacheCleanUp(@Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void deleteAll() {
        List<String> keys = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(ProductDetailRedisCache.KEY_PREFIX + "*").count(500).build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            cursor.forEachRemaining(keys::add);
        }
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}

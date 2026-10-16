package com.loopers.support.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CacheModeTest {
    private CacheVersions versions() {
        return new CacheVersions(mock(org.springframework.jdbc.core.JdbcTemplate.class),
            new WriteInvalidation(false, mock(RedisTemplate.class), new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }
    @Test void disabledCacheReadsOriginalWithoutRedis() {
        var redis = mock(CachedJson.class);
        var cache = new ResponseCache(redis, false, new ObjectMapper(), versions());
        assertThat(cache.read("products", CacheKeys.product(1L), new TypeReference<String>() { }, () -> "database")).isEqualTo("database");
        verifyNoInteractions(redis);
    }
    @Test void enabledCacheReusesJsonWithoutLoadingOriginal() {
        var redis = mock(CachedJson.class);
        when(redis.load(anyString(), anyString(), any())).thenReturn("\"cached\"");
        var cache = new ResponseCache(redis, true, new ObjectMapper(), versions());
        assertThat(cache.read("products", CacheKeys.product(1L), new TypeReference<String>() { }, () -> {
            throw new AssertionError("hit must not load original");
        })).isEqualTo("cached");
        assertThat(CacheKeys.product(1L)).isEqualTo("v4:products/1");
    }
    @Test void disabledCacheDoesNotRegisterInvalidation() {
        var redis = mock(RedisTemplate.class);
        var invalidation = new WriteInvalidation(false, redis, new SimpleMeterRegistry());
        invalidation.changed(CacheKeys.CATALOG_GROUP);
        verifyNoInteractions(redis);
    }
}

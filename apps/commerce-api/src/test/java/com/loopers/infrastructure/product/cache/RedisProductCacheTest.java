package com.loopers.infrastructure.product.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.product.query.ProductCacheValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DR-37 캐시 구현의 키·직렬화와, Redis 장애를 miss·무시로 돌리는지. */
class RedisProductCacheTest {
    private static final ProductCacheValue.Item ITEM = new ProductCacheValue.Item(7L, "상품", 1000L, 3L, "브랜드");

    private ValueOperations<String, String> readOps;
    private ValueOperations<String, String> writeOps;
    private RedisProductCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RedisTemplate<String, String> readTemplate = mock(RedisTemplate.class);
        RedisTemplate<String, String> writeTemplate = mock(RedisTemplate.class);
        readOps = mock(ValueOperations.class);
        writeOps = mock(ValueOperations.class);
        when(readTemplate.opsForValue()).thenReturn(readOps);
        when(writeTemplate.opsForValue()).thenReturn(writeOps);
        cache = new RedisProductCache(readTemplate, writeTemplate, new ObjectMapper());
    }

    @DisplayName("[DR-37] 상세는 상품 id·상품 revision·브랜드 revision 키로 master 에 TTL 과 함께 쓰고, 같은 키를 읽기 템플릿에서 읽는다.")
    @Test
    void writesToMasterWithTtl_andReadsSameKey() {
        cache.putDetail(7L, 2L, 5L, ITEM);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(writeOps).set(eq("product:detail:v1:7:2:5"), json.capture(), eq(RedisProductCache.DETAIL_TTL));
        when(readOps.get("product:detail:v1:7:2:5")).thenReturn(json.getValue());

        assertThat(cache.findDetail(7L, 2L, 5L)).contains(ITEM);
        assertThat(cache.findDetail(7L, 3L, 5L)).isEmpty();
    }

    @DisplayName("[DR-37] 읽기에서 Redis 가 실패하면 예외 없이 miss 다.")
    @Test
    void readFailure_isMiss() {
        when(readOps.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(cache.findDetail(7L, 0L, 0L)).isEmpty();
    }

    @DisplayName("[DR-37] 쓰기에서 Redis 가 실패해도 예외를 던지지 않는다.")
    @Test
    void writeFailure_isIgnored() {
        doThrow(new RedisConnectionFailureException("down")).when(writeOps).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> cache.putDetail(7L, 0L, 0L, ITEM)).doesNotThrowAnyException();
    }

    @DisplayName("[DR-37] 저장된 값을 읽을 수 없으면 miss 다.")
    @Test
    void unreadableValue_isMiss() {
        when(readOps.get(anyString())).thenReturn("{not json");

        assertThat(cache.findDetail(7L, 0L, 0L)).isEmpty();
    }
}

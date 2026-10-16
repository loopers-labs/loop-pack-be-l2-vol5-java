package com.loopers.infrastructure.product.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.product.ProductListCacheException;
import com.loopers.domain.product.ProductListSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedisProductListCacheTest {
    private static final String KEY = "commerce:test:products:v1:brand=all:sort=likes_desc:page=0:size=20:rev=42";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String VALID = """
        {"revision":42,"page":{"items":[{"productId":7,"name":"상품","price":1000,
        "stockQuantity":3,"brandId":2,"brandName":"브랜드","likeCount":5}],
        "page":0,"size":20,"totalElements":1,"totalPages":1}}
        """;

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SimpleMeterRegistry registry;
    private RedisProductListCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        registry = new SimpleMeterRegistry();
        cache = new RedisProductListCache(redis, MAPPER, properties(true), registry);
    }

    @Test
    void roundTripsOnlyThePublicSnapshotAndStoresValueWithExpiryInOneOperation() throws Exception {
        cache.store(snapshot());

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq(KEY), body.capture(), eq(Duration.ofSeconds(60)));
        JsonNode json = MAPPER.readTree(body.getValue());
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("revision", "page");
        assertThat(json.path("page").path("items").get(0).fieldNames()).toIterable()
            .containsExactlyInAnyOrder("productId", "name", "price", "stockQuantity", "brandId", "brandName", "likeCount");
        when(values.get(KEY)).thenReturn(body.getValue());

        assertThat(cache.find(42)).contains(snapshot());
        assertThat(count("lookup", "hit")).isEqualTo(1);
        assertThat(count("store", "success")).isEqualTo(1);
    }

    @Test
    void missingKeyIsAnObservableMissAndDoesNotWriteOrExtendTtl() {
        assertThat(cache.find(42)).isEmpty();
        verify(values).get(KEY);
        assertThat(count("lookup", "miss")).isEqualTo(1);
    }

    @Test
    void disabledCacheDoesNotAccessRedis() {
        RedisProductListCache disabled = new RedisProductListCache(redis, MAPPER, properties(false), registry);
        assertThat(disabled.isEnabled()).isFalse();
        disabled.store(snapshot());
        assertThat(disabled.find(42)).isEmpty();
        verifyNoInteractions(values);
    }

    @ParameterizedTest
    @MethodSource("corruptPayloads")
    void rejectsCorruptSchemaTypesRangesAndPageSemanticsAsObservableMiss(String invalid) {
        when(values.get(KEY)).thenReturn(invalid);
        assertThat(cache.find(42)).isEmpty();
        assertThat(count("lookup", "invalid")).isEqualTo(1);
    }

    @Test
    void connectionFailureIsDistinctFromMissingKey() {
        when(values.get(KEY)).thenThrow(new RedisConnectionFailureException("test outage"));
        assertThatThrownBy(() -> cache.find(42)).isInstanceOf(ProductListCacheException.class);
        assertThat(count("lookup", "error")).isEqualTo(1);
        assertThat(count("lookup", "miss")).isZero();
    }

    @Test
    void failedSetIsReportedWithoutRetry() throws Exception {
        doThrow(new RedisConnectionFailureException("test outage")).when(values)
            .set(eq(KEY), eq(MAPPER.writeValueAsString(snapshot())), eq(Duration.ofSeconds(60)));
        assertThatThrownBy(() -> cache.store(snapshot())).isInstanceOf(ProductListCacheException.class);
        assertThat(count("store", "error")).isEqualTo(1);
    }

    @Test
    void emptyPageAndMaximumBusinessNumericValuesRemainValid() throws Exception {
        ProductListSnapshot empty = new ProductListSnapshot(42, PageResult.of(List.of(), 0, 20, 0));
        when(values.get(KEY)).thenReturn(MAPPER.writeValueAsString(empty));
        assertThat(cache.find(42)).contains(empty);
        ProductListSnapshot maximum = new ProductListSnapshot(42, PageResult.of(List.of(
            new ProductListSnapshot.Item(Long.MAX_VALUE, "😀".repeat(100), Long.MAX_VALUE,
                Integer.MAX_VALUE, Long.MAX_VALUE, "브랜드", Long.MAX_VALUE)), 0, 20, 1));
        when(values.get(KEY)).thenReturn(MAPPER.writeValueAsString(maximum));
        assertThat(cache.find(42)).contains(maximum);
    }

    @Test
    void rejectsDuplicateProductsAndWrongRankingEvenWhenEveryIndividualValueIsValid() throws Exception {
        JsonNode item = MAPPER.readTree(VALID).path("page").path("items").get(0);
        String duplicate = MAPPER.writeValueAsString(Map.of("revision", 42, "page", Map.of(
            "items", List.of(item, item), "page", 0, "size", 20, "totalElements", 2, "totalPages", 1)));
        when(values.get(KEY)).thenReturn(duplicate);
        assertThat(cache.find(42)).isEmpty();

        String wrongRanking = duplicate.replaceFirst("\"productId\":7", "\"productId\":6");
        when(values.get(KEY)).thenReturn(wrongRanking);
        assertThat(cache.find(42)).isEmpty();
        assertThat(count("lookup", "invalid")).isEqualTo(2);
    }

    static Stream<String> corruptPayloads() {
        return Stream.of(
            "not json", "null", "{}", VALID + " {}", "{\"revision\":42,\"page\":null}",
            VALID.replace("\"revision\":42", "\"revision\":42,\"revision\":42"),
            VALID.replace("\"revision\":42", "\"revision\":43"),
            VALID.replace("\"revision\":42", "\"revision\":null"),
            VALID.replace("\"revision\":42,", ""),
            VALID.replace("\"productId\":7", "\"productId\":\"7\""),
            VALID.replace("\"productId\":7", "\"productId\":7.5"),
            VALID.replace("\"productId\":7", "\"productId\":0"),
            VALID.replace("\"productId\":7", "\"productId\":null"),
            VALID.replace("\"brandId\":2", "\"brandId\":-1"),
            VALID.replace("\"name\":\"상품\"", "\"name\":42"),
            VALID.replace("\"name\":\"상품\"", "\"name\":\"  \""),
            VALID.replace("\"name\":\"상품\"", "\"name\":\"" + "가".repeat(101) + "\""),
            VALID.replace("\"brandName\":\"브랜드\"", "\"brandName\":null"),
            VALID.replace("\"price\":1000", "\"price\":0"),
            VALID.replace("\"stockQuantity\":3", "\"stockQuantity\":-1"),
            VALID.replace("\"stockQuantity\":3", "\"stockQuantity\":2147483648"),
            VALID.replace("\"likeCount\":5", "\"likeCount\":-1"),
            VALID.replace("\"likeCount\":5", "\"likeCount\":5,\"liked\":true"),
            VALID.replace("\"likeCount\":5", "\"likeCount\":5,\"userId\":1"),
            VALID.replace("\"page\":0", "\"page\":1"),
            VALID.replace("\"size\":20", "\"size\":10"),
            VALID.replace("\"totalElements\":1", "\"totalElements\":-1"),
            VALID.replace("\"totalElements\":1", "\"totalElements\":2"),
            VALID.replace("\"totalPages\":1", "\"totalPages\":0"),
            VALID.replace("\"totalPages\":1", "\"totalPages\":\"1\""));
    }

    static ProductListSnapshot snapshot() {
        return new ProductListSnapshot(42, PageResult.of(List.of(
            new ProductListSnapshot.Item(7L, "상품", 1000, 3, 2L, "브랜드", 5)), 0, 20, 1));
    }

    private static ProductListCacheProperties properties(boolean enabled) {
        return new ProductListCacheProperties(enabled, "commerce:test:products:v1", Duration.ofSeconds(60),
            Duration.ofMillis(100), Duration.ofMillis(100));
    }

    private double count(String operation, String outcome) {
        var counter = registry.find("commerce.product.list.cache").tags("operation", operation, "outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }
}

package com.loopers.infrastructure.product;

import com.loopers.application.product.cache.ProductListCache;
import com.loopers.application.product.dto.ProductListItemInfo;
import com.loopers.application.product.dto.ProductListQuery;
import com.loopers.application.product.cache.CachedProductListInfo;
import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.product.ProductSort;
import com.loopers.utils.RedisCleanUp;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ProductListRedisCacheTest {

    @Autowired
    private ProductListCache productListCache;

    @Autowired
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        redisCleanUp.truncateAll();
    }

    private static final ProductListQuery ALL_LATEST = new ProductListQuery(null, ProductSort.LATEST, 0, 20);
    private static final ProductListQuery BRAND_PRICE = new ProductListQuery(7L, ProductSort.PRICE_ASC, 1, 10);

    private static CachedProductListInfo snapshot() {
        return new CachedProductListInfo(
            List.of(
                new CachedProductListInfo.Item(3L, "티셔츠", 3_000, new ProductListItemInfo.BrandSummary(7L, "루퍼스")),
                new CachedProductListInfo.Item(2L, "양말", 2_000, new ProductListItemInfo.BrandSummary(8L, "오프"))
            ),
            2
        );
    }

    private double errorCount(String operation) {
        Counter counter = meterRegistry.find("product.list.cache.errors").tag("operation", operation).counter();
        return counter == null ? 0 : counter.count();
    }

    @DisplayName("저장과 조회")
    @Nested
    class SaveAndFind {

        @DisplayName("저장한 목록을 같은 조건으로 조회하면 같은 값을 돌려준다.")
        @Test
        void returnsSameSnapshot_whenQueryIsSame() {
            productListCache.save(ALL_LATEST, snapshot());

            assertThat(productListCache.find(ALL_LATEST)).contains(snapshot());
        }

        @DisplayName("조건 중 하나라도 다르면 찾지 못한다.")
        @Test
        void returnsEmpty_whenAnyConditionDiffers() {
            productListCache.save(ALL_LATEST, snapshot());

            assertThat(productListCache.find(new ProductListQuery(7L, ProductSort.LATEST, 0, 20))).isEmpty();
            assertThat(productListCache.find(new ProductListQuery(null, ProductSort.PRICE_ASC, 0, 20))).isEmpty();
            assertThat(productListCache.find(new ProductListQuery(null, ProductSort.LATEST, 1, 20))).isEmpty();
            assertThat(productListCache.find(new ProductListQuery(null, ProductSort.LATEST, 0, 10))).isEmpty();
        }

        @DisplayName("키는 형식 버전과 브랜드(없으면 all)·정렬·페이지·크기로 만든다.")
        @Test
        void storesUnderVersionedKey() {
            productListCache.save(ALL_LATEST, snapshot());
            productListCache.save(BRAND_PRICE, snapshot());

            assertThat(redisTemplate.hasKey("product:list:v1:brand=all:sort=latest:page=0:size=20")).isTrue();
            assertThat(redisTemplate.hasKey("product:list:v1:brand=7:sort=price_asc:page=1:size=10")).isTrue();
        }

        @DisplayName("저장한 값에는 TTL(설정값 60초)이 걸리고, 조회해도 늘어나지 않는다.")
        @Test
        void setsTtl_andDoesNotExtendOnRead() {
            productListCache.save(ALL_LATEST, snapshot());
            String key = ProductListRedisCache.keyOf(ALL_LATEST);
            long before = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);

            productListCache.find(ALL_LATEST);

            long after = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
            assertThat(before).isPositive().isLessThanOrEqualTo(60_000);
            assertThat(after).isLessThanOrEqualTo(before);
        }

        @DisplayName("빈 목록도 저장하고 그대로 돌려준다.")
        @Test
        void returnsEmptyList_whenSnapshotHasNoItems() {
            CachedProductListInfo empty = new CachedProductListInfo(List.of(), 0);
            productListCache.save(BRAND_PRICE, empty);

            assertThat(productListCache.find(BRAND_PRICE)).contains(empty);
        }
    }

    @DisplayName("깨진 값")
    @Nested
    class BrokenPayload {

        @DisplayName("JSON 이 아닌 값은 없음으로 돌려주고 오류 카운터를 올린다.")
        @Test
        void returnsEmpty_andCountsError_whenPayloadIsNotJson() {
            double before = errorCount("find");
            redisTemplate.opsForValue().set(ProductListRedisCache.keyOf(ALL_LATEST), "not-json");

            assertThat(productListCache.find(ALL_LATEST)).isEmpty();
            assertThat(errorCount("find")).isEqualTo(before + 1);
        }

        @DisplayName("목록 항목이 빠진 값은 빈 목록으로 읽지 않고 없음으로 돌려준다.")
        @Test
        void returnsEmpty_whenItemsAreMissing() {
            double before = errorCount("find");
            redisTemplate.opsForValue().set(ProductListRedisCache.keyOf(ALL_LATEST), "{\"totalElements\":0}");

            assertThat(productListCache.find(ALL_LATEST)).isEmpty();
            assertThat(errorCount("find")).isEqualTo(before + 1);
        }

        @DisplayName("값이 없는 정상 miss 는 오류로 세지 않는다.")
        @Test
        void doesNotCountError_whenKeyIsAbsent() {
            double before = errorCount("find");

            assertThat(productListCache.find(ALL_LATEST)).isEmpty();
            assertThat(errorCount("find")).isEqualTo(before);
        }
    }

    @DisplayName("영역 비우기")
    @Nested
    class EvictAll {

        @DisplayName("상품 목록 키만 모두 지우고 다른 키는 남긴다.")
        @Test
        void deletesOnlyProductListKeys() {
            productListCache.save(ALL_LATEST, snapshot());
            productListCache.save(BRAND_PRICE, snapshot());
            redisTemplate.opsForValue().set("product:detail:1", "keep");
            redisTemplate.opsForValue().set("other:key", "keep");

            productListCache.evictAll();

            assertThat(redisTemplate.keys("product:list:*")).isEmpty();
            assertThat(redisTemplate.hasKey("product:detail:1")).isTrue();
            assertThat(redisTemplate.hasKey("other:key")).isTrue();
        }

        @DisplayName("한 번에 지우는 묶음(500개)보다 키가 많아도 모두 지운다.")
        @Test
        void deletesAllKeys_whenKeysExceedBatchSize() {
            IntStream.range(0, 1_201).forEach(page ->
                productListCache.save(new ProductListQuery(null, ProductSort.LATEST, page, 20), snapshot()));

            productListCache.evictAll();

            assertThat(redisTemplate.keys("product:list:*")).isEmpty();
        }

        @DisplayName("지울 키가 없어도 실패하지 않는다.")
        @Test
        void succeeds_whenNoKeyExists() {
            double before = errorCount("evict");

            productListCache.evictAll();

            assertThat(errorCount("evict")).isEqualTo(before);
        }
    }
}

package com.loopers.infrastructure.cache;

import com.loopers.application.product.CacheResult;
import com.loopers.application.product.CachedBrand;
import com.loopers.application.product.CachedProduct;
import com.loopers.application.product.CachedProductIds;
import com.loopers.application.product.ProductListLookup;
import com.loopers.domain.product.ProductSort;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
class RedisProductCacheTest {

    @Autowired
    private RedisProductCache productCache;

    @Autowired
    @Qualifier(CacheRedisConfig.CACHE_REDIS_TEMPLATE)
    private RedisTemplate<String, String> cacheRedisTemplate;

    @Autowired
    private RedisCleanUp redisCleanUp;

    // 다른 테스트 클래스의 관리자 쓰기가 남긴 목록 버전을 지우고 시작한다 — 키의 버전(v0)을 정확히 비교하기 위해서
    @BeforeEach
    void setUp() {
        redisCleanUp.truncateAll();
    }

    @AfterEach
    void tearDown() {
        redisCleanUp.truncateAll();
    }

    @DisplayName("여러 키를 저장해도 Redis 연결을 새로 맺지 않는다 — 공유 연결을 쓴다.")
    @Test
    void putsWithoutOpeningNewConnections() {
        // arrange — 공유 연결을 먼저 맺어 둔다
        productCache.putProducts(List.of(new CachedProduct(999L, "준비", 1L, 1, 1L, 0L)));
        long before = connectionsReceived();
        List<CachedProduct> products = LongStream.rangeClosed(1, 20)
            .mapToObj(id -> new CachedProduct(id, "상품" + id, 1_000L * id, 10, 7L, 0L))
            .toList();

        // act — 목록 miss 한 번이 하는 저장과 같은 양
        productCache.putProductIds(productCache.lookupProductIds(7L, ProductSort.PRICE_ASC, 0, 20),
            new CachedProductIds(products.stream().map(CachedProduct::id).toList(), 20L));
        productCache.putProducts(products);
        productCache.putBrands(List.of(new CachedBrand(7L, "나이키")));

        // assert — 파이프라인은 호출마다 전용 연결을 새로 맺었다(재측정에서 miss 요청당 약 15개)
        assertThat(connectionsReceived()).isEqualTo(before);
    }

    @DisplayName("상품 20개를 저장해도 Redis 왕복은 한 번이다 — 스크립트 한 번으로 저장한다.")
    @Test
    void putsManyKeysInOneRoundTrip() {
        // arrange — 첫 호출은 스크립트가 서버에 없어 EVALSHA 실패 후 EVAL로 등록되므로, 한 번 먼저 저장해 둔다
        productCache.putProducts(List.of(new CachedProduct(999L, "준비", 1L, 1, 1L, 0L)));
        long before = scriptCalls();
        List<CachedProduct> products = LongStream.rangeClosed(1, 20)
            .mapToObj(id -> new CachedProduct(id, "상품" + id, 1_000L * id, 10, 7L, 0L))
            .toList();

        // act
        productCache.putProducts(products);

        // assert — 키마다 SET을 차례로 보내면 왕복이 20번이었다(로컬 약 0.9ms씩, 목록 miss 54ms)
        assertAll(
            () -> assertThat(scriptCalls()).isEqualTo(before + 1),
            () -> assertThat(productCache.getProducts(products.stream().map(CachedProduct::id).toList()))
                .isEqualTo(CacheResult.hit(products.stream().collect(
                    Collectors.toMap(CachedProduct::id, product -> product)))),
            () -> assertThat(cacheRedisTemplate.getExpire("product:20")).isBetween(1L, RedisProductCache.TTL_SECONDS)
        );
    }

    // 스크립트 실행 명령(EVALSHA·EVAL) 호출 수의 합
    private long scriptCalls() {
        Properties stats = cacheRedisTemplate.execute(
            (RedisCallback<Properties>) connection -> connection.serverCommands().info("commandstats"));
        return callsOf(stats, "cmdstat_evalsha") + callsOf(stats, "cmdstat_eval");
    }

    private static long callsOf(Properties stats, String command) {
        String value = stats.getProperty(command);
        if (value == null) {
            return 0;
        }
        return Long.parseLong(value.split(",")[0].substring("calls=".length()));
    }

    private long connectionsReceived() {
        Properties stats = cacheRedisTemplate.execute(
            (RedisCallback<Properties>) connection -> connection.serverCommands().info("stats"));
        return Long.parseLong(stats.getProperty("total_connections_received"));
    }

    @DisplayName("상품·브랜드 캐시를 다룰 때,")
    @Nested
    class ProductsAndBrands {
        @DisplayName("저장한 상품을 MGET으로 다시 읽으면 같은 값이 나온다.")
        @Test
        void roundTripsProducts() {
            // arrange
            CachedProduct a = new CachedProduct(1L, "에어맥스", 129_000L, 10, 7L, 3L);
            CachedProduct b = new CachedProduct(2L, "조던", 199_000L, 0, 7L, 0L);
            productCache.putProducts(List.of(a, b));

            // act
            CacheResult<Map<Long, CachedProduct>> result = productCache.getProducts(List.of(1L, 2L));

            // assert
            assertThat(result).isEqualTo(CacheResult.hit(Map.of(1L, a, 2L, b)));
        }

        @DisplayName("일부 id만 캐시에 있으면, 있는 것만 hit map에 담기고 없는 id는 빠진다.")
        @Test
        void returnsOnlyCachedProducts_whenSomeAreMissing() {
            // arrange
            CachedProduct a = new CachedProduct(1L, "에어맥스", 129_000L, 10, 7L, 3L);
            productCache.putProducts(List.of(a));

            // act
            CacheResult<Map<Long, CachedProduct>> result = productCache.getProducts(List.of(1L, 2L, 3L));

            // assert
            assertThat(result).isEqualTo(CacheResult.hit(Map.of(1L, a)));
        }

        @DisplayName("하나도 캐시에 없으면, 빈 map의 hit이다(Redis 실패와 구분된다).")
        @Test
        void returnsEmptyHit_whenNothingIsCached() {
            // act
            CacheResult<Map<Long, CachedProduct>> result = productCache.getProducts(List.of(1L, 2L));

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo(CacheResult.hit(Map.of())),
                () -> assertThat(result.isUnavailable()).isFalse()
            );
        }

        @DisplayName("역직렬화할 수 없는 값은 그 키만 miss로 취급한다.")
        @Test
        void treatsUndeserializableValueAsMiss() {
            // arrange
            CachedProduct a = new CachedProduct(1L, "에어맥스", 129_000L, 10, 7L, 3L);
            productCache.putProducts(List.of(a));
            cacheRedisTemplate.opsForValue().set("product:2", "not-json");

            // act
            CacheResult<Map<Long, CachedProduct>> result = productCache.getProducts(List.of(1L, 2L));

            // assert
            assertThat(result).isEqualTo(CacheResult.hit(Map.of(1L, a)));
        }

        @DisplayName("저장한 브랜드를 다시 읽으면 같은 값이 나온다.")
        @Test
        void roundTripsBrands() {
            // arrange
            CachedBrand nike = new CachedBrand(7L, "나이키");
            productCache.putBrands(List.of(nike));

            // act
            CacheResult<Map<Long, CachedBrand>> result = productCache.getBrands(List.of(7L, 8L));

            // assert
            assertThat(result).isEqualTo(CacheResult.hit(Map.of(7L, nike)));
        }

        @DisplayName("상품·브랜드 키는 TTL이 60초 이하로 설정된다.")
        @Test
        void setsTtlWithinSixtySeconds() {
            // arrange
            productCache.putProducts(List.of(new CachedProduct(1L, "에어맥스", 129_000L, 10, 7L, 3L)));
            productCache.putBrands(List.of(new CachedBrand(7L, "나이키")));

            // act
            Long productTtl = cacheRedisTemplate.getExpire("product:1");
            Long brandTtl = cacheRedisTemplate.getExpire("brand:7");

            // assert
            assertAll(
                () -> assertThat(productTtl).isBetween(1L, 60L),
                () -> assertThat(brandTtl).isBetween(1L, 60L)
            );
        }

        @DisplayName("상품 키를 지우면 그 상품만 miss가 되고, 브랜드 키를 지우면 그 브랜드만 miss가 된다.")
        @Test
        void evictsOnlyGivenKeys() {
            // arrange
            CachedProduct a = new CachedProduct(1L, "에어맥스", 129_000L, 10, 7L, 3L);
            CachedProduct b = new CachedProduct(2L, "조던", 199_000L, 0, 7L, 0L);
            productCache.putProducts(List.of(a, b));
            productCache.putBrands(List.of(new CachedBrand(7L, "나이키"), new CachedBrand(8L, "아디다스")));

            // act
            productCache.evictProducts(List.of(1L));
            productCache.evictBrand(7L);

            // assert
            assertAll(
                () -> assertThat(productCache.getProducts(List.of(1L, 2L))).isEqualTo(CacheResult.hit(Map.of(2L, b))),
                () -> assertThat(productCache.getBrands(List.of(7L, 8L)))
                    .isEqualTo(CacheResult.hit(Map.of(8L, new CachedBrand(8L, "아디다스"))))
            );
        }
    }

    @DisplayName("목록 캐시를 다룰 때,")
    @Nested
    class ProductList {
        @DisplayName("버전이 없으면 0으로 보고, lookup의 키에 저장한 값을 같은 조건의 다음 lookup에서 읽는다.")
        @Test
        void roundTripsProductIds() {
            // arrange
            CachedProductIds ids = new CachedProductIds(List.of(3L, 1L, 2L), 3L);
            ProductListLookup first = productCache.lookupProductIds(7L, ProductSort.PRICE_ASC, 0, 20);

            // act
            boolean stored = productCache.putProductIds(first, ids);
            ProductListLookup second = productCache.lookupProductIds(7L, ProductSort.PRICE_ASC, 0, 20);

            // assert
            assertAll(
                () -> assertThat(first.result()).isEqualTo(CacheResult.miss()),
                () -> assertThat(first.key()).isEqualTo("product:list:v0:7:price_asc:0:20"),
                () -> assertThat(stored).isTrue(),
                () -> assertThat(second.result()).isEqualTo(CacheResult.hit(ids))
            );
        }

        @DisplayName("brandId가 없으면 all, 정렬은 API 쿼리 값(소문자)으로 키를 만든다.")
        @Test
        void buildsKeyWithAllAndQueryValue() {
            // act
            ProductListLookup lookup = productCache.lookupProductIds(null, ProductSort.LIKES_DESC, 2, 10);

            // assert
            assertThat(lookup.key()).isEqualTo("product:list:v0:all:likes_desc:2:10");
        }

        @DisplayName("버전을 올린 뒤에는 이전 lookup으로 저장한 값이 새 lookup에서 miss다 — 저장 때 버전을 다시 읽지 않는다.")
        @Test
        void putWithStaleLookup_isNotVisibleAfterVersionBump() {
            // arrange — 조회가 버전 0을 읽은 사이에 순서를 바꾸는 쓰기가 버전을 올린다
            ProductListLookup staleLookup = productCache.lookupProductIds(7L, ProductSort.LATEST, 0, 20);
            productCache.bumpListVersion();

            // act — 늦게 도착한 채우기
            productCache.putProductIds(staleLookup, new CachedProductIds(List.of(1L), 1L));
            ProductListLookup fresh = productCache.lookupProductIds(7L, ProductSort.LATEST, 0, 20);

            // assert
            assertAll(
                () -> assertThat(fresh.key()).isEqualTo("product:list:v1:7:latest:0:20"),
                () -> assertThat(fresh.result()).isEqualTo(CacheResult.miss()),
                () -> assertThat(cacheRedisTemplate.hasKey("product:list:v0:7:latest:0:20")).isTrue()
            );
        }

        @DisplayName("목록 키는 TTL이 60초 이하로 설정되고, 버전 키에는 TTL이 없다.")
        @Test
        void setsTtlOnListKey_andNoTtlOnVersionKey() {
            // arrange
            productCache.bumpListVersion();
            ProductListLookup lookup = productCache.lookupProductIds(7L, ProductSort.LATEST, 0, 20);
            productCache.putProductIds(lookup, new CachedProductIds(List.of(1L), 1L));

            // act
            Long listTtl = cacheRedisTemplate.getExpire(lookup.key());
            Long versionTtl = cacheRedisTemplate.getExpire(RedisProductCache.LIST_VERSION_KEY);

            // assert
            assertAll(
                () -> assertThat(listTtl).isBetween(1L, 60L),
                () -> assertThat(versionTtl).isEqualTo(-1L)
            );
        }
    }
}

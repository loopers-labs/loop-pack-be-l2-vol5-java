package com.loopers.application.product;

import com.loopers.config.cache.ProductCacheRedisConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import com.loopers.utils.SqlCounter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 RedisProductListCache + 테스트 컨테이너 Redis 로 상품 목록 miss / hit 를 확인한다.
 * DB-only 결과는 ProductQueryWithoutCacheTest 가 같은 고정 기대값(ProductQueryFixture)과 비교한다.
 */
@SpringBootTest
@Sql("/sql/product-perf-seed.sql")
class ProductCacheQueryTest {

    private final ProductFacade productFacade;
    private final RedisTemplate<String, String> cacheRedisTemplate;
    private final MeterRegistry meterRegistry;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;

    private SqlCounter sqlCounter;

    @Autowired
    public ProductCacheQueryTest(
        ProductFacade productFacade,
        @Qualifier(ProductCacheRedisConfig.PRODUCT_CACHE_REDIS_TEMPLATE) RedisTemplate<String, String> cacheRedisTemplate,
        MeterRegistry meterRegistry,
        EntityManager entityManager,
        PlatformTransactionManager transactionManager,
        DatabaseCleanUp databaseCleanUp,
        RedisCleanUp redisCleanUp
    ) {
        this.productFacade = productFacade;
        this.cacheRedisTemplate = cacheRedisTemplate;
        this.meterRegistry = meterRegistry;
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.databaseCleanUp = databaseCleanUp;
        this.redisCleanUp = redisCleanUp;
    }

    @BeforeEach
    void setUp() {
        sqlCounter = new SqlCounter(entityManager.getEntityManagerFactory());
    }

    @AfterEach
    void tearDown() {
        sqlCounter.disable();
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private List<ProductInfo> queryFixturePage() {
        return productFacade.getProducts(
            ProductQueryFixture.BRAND_ID, ProductQueryFixture.SORT, ProductQueryFixture.PAGE, ProductQueryFixture.SIZE);
    }

    @DisplayName("브랜드 7 가격순 첫 페이지를 캐시로 조회할 때, ")
    @Nested
    class MissAndHit {
        @DisplayName("miss 결과와 hit 결과는 고정 기대값(= DB-only 결과)과 같다.")
        @Test
        void missAndHitMatchFixture() {
            // act
            List<ProductInfo> miss = queryFixturePage();
            List<ProductInfo> hit = queryFixturePage();

            // assert
            assertThat(miss).containsExactlyElementsOf(ProductQueryFixture.EXPECTED_PAGE_0);
            assertThat(hit).containsExactlyElementsOf(ProductQueryFixture.EXPECTED_PAGE_0);
            assertThat(hit).isEqualTo(miss);
        }

        @DisplayName("miss 뒤에는 컨테이너 Redis 에 해당 키가 생긴다.")
        @Test
        void createsKey_afterMiss() {
            // arrange
            assertThat(cacheRedisTemplate.hasKey(ProductQueryFixture.CACHE_KEY)).isFalse();

            // act
            queryFixturePage();

            // assert
            assertThat(cacheRedisTemplate.hasKey(ProductQueryFixture.CACHE_KEY)).isTrue();
        }

        @DisplayName("miss 는 SQL 3개, hit 는 삭제 여부·재고 조회 SQL 1개로 끝난다.")
        @Test
        void hitExecutesSingleStatement() {
            // act
            sqlCounter.reset();
            queryFixturePage();
            long missStatements = sqlCounter.count();

            sqlCounter.reset();
            queryFixturePage();
            long hitStatements = sqlCounter.count();

            // assert
            assertThat(missStatements).isEqualTo(3);
            assertThat(hitStatements).isEqualTo(1);
        }

        @DisplayName("캐시에 담긴 상품이 DB 에서 삭제 표시되면(캐시는 그대로), 삭제 상품이 빠진 DB 결과가 나온다.")
        @Test
        void fallsBackToDb_whenCachedProductIsDeleted() {
            // arrange
            queryFixturePage();
            Long deletedId = ProductQueryFixture.EXPECTED_PAGE_0.get(0).productId();
            transactionTemplate.executeWithoutResult(status -> entityManager
                .createNativeQuery("UPDATE products SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", deletedId)
                .executeUpdate());
            assertThat(cacheRedisTemplate.hasKey(ProductQueryFixture.CACHE_KEY)).isTrue();

            // act
            List<ProductInfo> result = queryFixturePage();

            // assert
            List<ProductInfo> expected = new ArrayList<>(ProductQueryFixture.EXPECTED_PAGE_0.subList(1, 20));
            expected.add(ProductQueryFixture.EXPECTED_21ST);
            assertThat(result).containsExactlyElementsOf(expected);
        }
    }

    @DisplayName("캐시 조건 밖의 요청은, ")
    @Nested
    class OutOfCacheCondition {
        @DisplayName("브랜드 없음 / latest / size 30 / page 3 모두 Redis 에 키를 만들지 않고, 카운터도 세지 않는다.")
        @Test
        void doesNotCreateKeys() {
            // arrange
            CacheCounterSnapshot before = CacheCounterSnapshot.take(meterRegistry);

            // act
            productFacade.getProducts(null, "price_asc", 0, 20);
            productFacade.getProducts(7L, "latest", 0, 20);
            productFacade.getProducts(7L, "price_asc", 0, 30);
            productFacade.getProducts(7L, "price_asc", 3, 20);

            // assert
            assertThat(cacheRedisTemplate.keys("products:*")).isEmpty();
            assertThat(before.deltaTo(CacheCounterSnapshot.take(meterRegistry))).isEqualTo(CacheCounterSnapshot.none());
        }
    }

    @DisplayName("캐시 카운터(product.list.cache)는, ")
    @Nested
    class CacheCounters {
        @DisplayName("miss 1회는 miss 만 1 늘고, 이어지는 hit 1회는 hit 만 1 는다.")
        @Test
        void countsMissThenHit() {
            // act & assert: miss
            CacheCounterSnapshot before = CacheCounterSnapshot.take(meterRegistry);
            queryFixturePage();
            assertThat(before.deltaTo(CacheCounterSnapshot.take(meterRegistry)))
                .isEqualTo(CacheCounterSnapshot.only("miss"));

            // act & assert: hit
            before = CacheCounterSnapshot.take(meterRegistry);
            queryFixturePage();
            assertThat(before.deltaTo(CacheCounterSnapshot.take(meterRegistry)))
                .isEqualTo(CacheCounterSnapshot.only("hit"));
        }

        @DisplayName("캐시 상품이 삭제돼 DB 로 전환하면 hit_check_failed 만 1 는다 (hit·miss 는 0).")
        @Test
        void countsHitCheckFailed_whenCachedProductIsDeleted() {
            // arrange
            queryFixturePage();
            Long deletedId = ProductQueryFixture.EXPECTED_PAGE_0.get(0).productId();
            transactionTemplate.executeWithoutResult(status -> entityManager
                .createNativeQuery("UPDATE products SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", deletedId)
                .executeUpdate());
            CacheCounterSnapshot before = CacheCounterSnapshot.take(meterRegistry);

            // act
            queryFixturePage();

            // assert
            assertThat(before.deltaTo(CacheCounterSnapshot.take(meterRegistry)))
                .isEqualTo(CacheCounterSnapshot.only("hit_check_failed"));
        }
    }
}

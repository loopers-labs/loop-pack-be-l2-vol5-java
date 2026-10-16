package com.loopers.infrastructure.product;

import com.loopers.config.cache.ProductCacheRedisConfig;
import com.loopers.domain.product.CachedProductItem;
import com.loopers.domain.product.ProductListCache;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 상품 목록 캐시의 저장·조회를 실제 Redis 컨테이너로 확인한다. 키·TTL 은 캐시 전용 템플릿으로 직접 들여다본다.
 */
@SpringBootTest
class RedisProductCacheTest {

    private static final List<CachedProductItem> ITEMS = List.of(
        new CachedProductItem(11L, "가장 싼 상품", 1000L, 7L, "brand-7", 3L),
        new CachedProductItem(12L, "중간 상품", 2000L, 7L, "brand-7", 0L),
        new CachedProductItem(10L, "비싼 상품", 3000L, 7L, "brand-7", 12L)
    );

    private final ProductListCache productListCache;
    private final RedisTemplate<String, String> cacheRedisTemplate;
    private final TransactionTemplate transactionTemplate;
    private final RedisCleanUp redisCleanUp;

    @Autowired
    public RedisProductCacheTest(
        ProductListCache productListCache,
        @Qualifier(ProductCacheRedisConfig.PRODUCT_CACHE_REDIS_TEMPLATE) RedisTemplate<String, String> cacheRedisTemplate,
        PlatformTransactionManager transactionManager,
        RedisCleanUp redisCleanUp
    ) {
        this.productListCache = productListCache;
        this.cacheRedisTemplate = cacheRedisTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.redisCleanUp = redisCleanUp;
    }

    @AfterEach
    void tearDown() {
        redisCleanUp.truncateAll();
    }

    @DisplayName("상품 목록을 캐시에 저장하고 꺼낼 때, ")
    @Nested
    class SaveAndFind {
        @DisplayName("저장한 목록과 순서·필드가 같은 목록이 조회된다.")
        @Test
        void returnsSameItemsInSameOrder() {
            // arrange
            productListCache.save(7L, 0, ITEMS);

            // act
            Optional<List<CachedProductItem>> found = productListCache.find(7L, 0);

            // assert
            assertThat(found).isPresent();
            assertThat(found.get()).containsExactlyElementsOf(ITEMS);
        }

        @DisplayName("저장하지 않은 브랜드·페이지를 조회하면 빈 결과가 나온다.")
        @Test
        void returnsEmpty_whenNotSaved() {
            // arrange
            productListCache.save(7L, 0, ITEMS);

            // act & assert
            assertThat(productListCache.find(7L, 1)).isEmpty();
            assertThat(productListCache.find(8L, 0)).isEmpty();
        }

        @DisplayName("키는 products:v1:brand={브랜드}:sort=price_asc:page={페이지}:size=20 형식이다.")
        @Test
        void usesExpectedKeyFormat() {
            // act
            productListCache.save(7L, 2, ITEMS);

            // assert
            Set<String> keys = cacheRedisTemplate.keys("products:*");
            assertThat(keys).containsExactly("products:v1:brand=7:sort=price_asc:page=2:size=20");
        }

        @DisplayName("저장된 키의 TTL 은 0초 초과 30초 이하다.")
        @Test
        void setsTtlWithin30Seconds() {
            // act
            productListCache.save(7L, 0, ITEMS);

            // assert
            Long ttlSeconds = cacheRedisTemplate.getExpire("products:v1:brand=7:sort=price_asc:page=0:size=20", TimeUnit.SECONDS);
            assertThat(ttlSeconds).isNotNull();
            assertThat(ttlSeconds).isGreaterThan(0L).isLessThanOrEqualTo(30L);
        }

        @DisplayName("여러 번 조회해도 TTL 이 늘어나지 않는다.")
        @Test
        void doesNotExtendTtl_whenFoundRepeatedly() throws InterruptedException {
            // arrange
            String key = "products:v1:brand=7:sort=price_asc:page=0:size=20";
            productListCache.save(7L, 0, ITEMS);
            Long before = cacheRedisTemplate.getExpire(key, TimeUnit.MILLISECONDS);

            // act
            Thread.sleep(200);
            for (int i = 0; i < 3; i++) {
                productListCache.find(7L, 0);
            }

            // assert
            Long after = cacheRedisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
            assertThat(before).isNotNull().isGreaterThan(0L);
            assertThat(after).isNotNull().isGreaterThan(0L).isLessThan(before);
        }
    }

    @DisplayName("commit 후 브랜드 캐시를 지우도록 등록할 때, ")
    @Nested
    class EvictBrandAfterCommit {
        @DisplayName("트랜잭션이 commit 되면 해당 브랜드의 page 0~2 키가 모두 없어진다.")
        @Test
        void removesBrandPages_afterCommit() {
            // arrange
            for (int page = 0; page <= 2; page++) {
                productListCache.save(7L, page, ITEMS);
            }

            // act
            transactionTemplate.executeWithoutResult(status -> productListCache.evictBrandAfterCommit(7L));

            // assert
            assertThat(cacheRedisTemplate.keys("products:v1:brand=7:*")).isEmpty();
        }

        @DisplayName("트랜잭션이 rollback 되면 키가 그대로 남아 있다.")
        @Test
        void keepsKeys_whenRolledBack() {
            // arrange
            for (int page = 0; page <= 2; page++) {
                productListCache.save(7L, page, ITEMS);
            }

            // act
            transactionTemplate.executeWithoutResult(status -> {
                productListCache.evictBrandAfterCommit(7L);
                status.setRollbackOnly();
            });

            // assert
            assertThat(cacheRedisTemplate.keys("products:v1:brand=7:*")).hasSize(3);
        }

        @DisplayName("다른 브랜드의 키는 남아 있다.")
        @Test
        void keepsOtherBrandKeys() {
            // arrange
            productListCache.save(7L, 0, ITEMS);
            productListCache.save(8L, 0, ITEMS);

            // act
            transactionTemplate.executeWithoutResult(status -> productListCache.evictBrandAfterCommit(7L));

            // assert
            assertThat(cacheRedisTemplate.keys("products:v1:brand=7:*")).isEmpty();
            assertThat(cacheRedisTemplate.keys("products:v1:brand=8:*"))
                .containsExactly("products:v1:brand=8:sort=price_asc:page=0:size=20");
        }

        @DisplayName("트랜잭션 밖에서 호출하면 IllegalStateException 이 발생한다.")
        @Test
        void throwsIllegalState_whenCalledOutsideTransaction() {
            // act & assert
            assertThrows(IllegalStateException.class, () -> productListCache.evictBrandAfterCommit(7L));
        }
    }
}

package com.loopers.application.product;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductListRevisionService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRevisionService;
import com.loopers.domain.product.ProductSortType;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.product.RedisProductCache;
import com.loopers.infrastructure.product.RedisProductListCache;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

// 이전 조회가 DB를 읽은 뒤 상품 캐시에 저장하기 전에 관리자 변경을 끼워 넣어, 늦은 캐시 채움을 결정적으로 재현한다
@SpringBootTest
@Import(ProductCacheLateFillTest.PausingCacheConfig.class)
class ProductCacheLateFillTest {

    private static final long TIMEOUT_SECONDS = 10;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private PausingProductCache pausingCache;

    @Autowired
    private RedisProductCache redisProductCache;

    @Autowired
    private RedisProductListCache redisProductListCache;

    @Autowired
    private ProductRevisionService productRevisionService;

    @Autowired
    private ProductListRevisionService productListRevisionService;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    private ExecutorService previousReader;
    private Long nikeId;
    private Long runnerId;

    @BeforeEach
    void setUp() {
        nikeId = brandJpaRepository.save(new BrandModel("나이키")).getId();
        runnerId = productJpaRepository.save(new ProductModel(nikeId, "runner", 30_000L, 5)).getId();
        productJpaRepository.save(new ProductModel(nikeId, "slipper", 10_000L, 3));
        previousReader = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pausingCache.resume();
        previousReader.shutdownNow();
        previousReader.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    @DisplayName("이전 조회가 가격 수정 전 상품을 늦게 캐시에 저장해도, 수정이 끝난 뒤 시작한 조회는 새 가격과 새 순서를 반환한다.")
    @Test
    void returnsNewPrice_evenIfPreviousReadFillsCacheLate() throws Exception {
        // arrange — 이전 조회가 DB에서 옛 가격을 읽고 상품 캐시 저장 직전에 멈춘다
        long revisionBefore = productRevision(runnerId);
        Future<List<ProductInfo>> previousRead = startPreviousReadPausedBeforeSave();

        // act — 관리자 변경 commit → 이전 조회가 옛 결과를 저장 → 새 조회
        productFacade.updateProduct(runnerId, "runner", 5_000L);
        pausingCache.resume();
        List<ProductInfo> previousResult = previousRead.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        List<ProductInfo> newResult = list();

        // assert
        assertAll(
            () -> assertThat(priceOf(previousResult, "runner")).as("이전 조회는 자신이 읽은 시점의 결과").isEqualTo(30_000L),
            () -> assertThat(cachedProduct(runnerId, revisionBefore).price()).as("옛 상품은 옛 revision 키에 늦게 저장됐다").isEqualTo(30_000L),
            () -> assertThat(names(newResult)).as("새 조회는 새 가격순").containsExactly("runner", "slipper"),
            () -> assertThat(priceOf(newResult, "runner")).isEqualTo(5_000L),
            () -> assertThat(priceOf(list(), "runner")).as("새 revision 키에 채워진 값도 새 가격").isEqualTo(5_000L)
        );
    }

    @DisplayName("목록 캐시가 그대로인 채로 이전 조회가 재고 변경 전 상품을 늦게 저장해도, 변경이 끝난 뒤 시작한 조회는 새 재고를 반환한다.")
    @Test
    void returnsNewStock_evenIfPreviousReadFillsProductCacheLate() throws Exception {
        // arrange — 목록 캐시만 남겨 두고, 이전 조회가 목록 hit 뒤 상품 캐시를 다시 채우다 멈추게 한다
        list();
        evictProductCacheOnly();
        long listRevision = productListRevisionService.getRevision(nikeId, ProductSortType.PRICE_ASC);
        long revisionBefore = productRevision(runnerId);
        Future<List<ProductInfo>> previousRead = startPreviousReadPausedBeforeSave();

        // act
        productFacade.changeStock(runnerId, 1);
        pausingCache.resume();
        previousRead.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        List<ProductInfo> newResult = list();

        // assert
        assertAll(
            () -> assertThat(productListRevisionService.getRevision(nikeId, ProductSortType.PRICE_ASC)).as("목록 revision은 그대로").isEqualTo(listRevision),
            () -> assertThat(cachedProduct(runnerId, revisionBefore).stock()).as("옛 재고는 옛 revision 키에 늦게 저장됐다").isEqualTo(5),
            () -> assertThat(stockOf(newResult, "runner")).as("새 조회는 새 재고").isEqualTo(1)
        );
    }

    @DisplayName("이전 조회가 삭제 전 목록을 늦게 캐시에 저장해도, 삭제가 끝난 뒤 시작한 조회에는 삭제된 상품이 나오지 않는다.")
    @Test
    void excludesDeletedProduct_evenIfPreviousReadFillsCacheLate() throws Exception {
        // arrange
        long listRevisionBefore = productListRevisionService.getRevision(nikeId, ProductSortType.PRICE_ASC);
        Future<List<ProductInfo>> previousRead = startPreviousReadPausedBeforeSave();

        // act
        productFacade.deleteProduct(runnerId);
        pausingCache.resume();
        previousRead.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        List<ProductInfo> newResult = list();

        // assert
        assertAll(
            () -> assertThat(cachedProductIds(listRevisionBefore)).as("옛 revision 키에는 삭제 전 목록").contains(runnerId),
            () -> assertThat(names(newResult)).containsExactly("slipper"),
            () -> assertThat(names(list())).containsExactly("slipper")
        );
    }

    private Future<List<ProductInfo>> startPreviousReadPausedBeforeSave() throws InterruptedException {
        pausingCache.pauseNextSave();
        Future<List<ProductInfo>> previousRead = previousReader.submit(this::list);
        assertThat(pausingCache.awaitPaused(TIMEOUT_SECONDS)).as("이전 조회가 DB를 읽고 저장 직전에 멈춤").isTrue();
        return previousRead;
    }

    // 목록 캐시는 두고 상품 캐시만 비워, 다음 조회가 목록 hit 뒤 상품을 DB에서 다시 읽게 한다
    private void evictProductCacheOnly() {
        long listRevision = productListRevisionService.getRevision(nikeId, ProductSortType.PRICE_ASC);
        List<Long> productIds = cachedProductIds(listRevision);
        redisCleanUp.truncateAll();
        redisProductListCache.save(listKey(listRevision), new ProductIdPage(productIds, productIds.size()));
        assertThat(redisProductCache.findAll(productRevisionService.getRevisions(productIds))).isEmpty();
    }

    private List<ProductInfo> list() {
        return productFacade.getProducts(nikeId, ProductSortType.PRICE_ASC, PageRequest.of(0, 20)).getContent();
    }

    private long productRevision(Long productId) {
        return productRevisionService.getRevisions(List.of(productId)).get(productId);
    }

    private ProductSnapshot cachedProduct(Long productId, long revision) {
        ProductSnapshot snapshot = redisProductCache.findAll(Map.of(productId, revision)).get(productId);
        assertThat(snapshot).isNotNull();
        return snapshot;
    }

    private List<Long> cachedProductIds(long listRevision) {
        return redisProductListCache.find(listKey(listRevision)).map(ProductIdPage::productIds).orElseThrow();
    }

    private ProductListCacheKey listKey(long listRevision) {
        return new ProductListCacheKey(nikeId, listRevision, ProductSortType.PRICE_ASC, 0, 20);
    }

    private Long priceOf(List<ProductInfo> products, String name) {
        return products.stream().filter(product -> product.name().equals(name)).findFirst().orElseThrow().price();
    }

    private int stockOf(List<ProductInfo> products, String name) {
        return products.stream().filter(product -> product.name().equals(name)).findFirst().orElseThrow().stock();
    }

    private List<String> names(List<ProductInfo> products) {
        return products.stream().map(ProductInfo::name).toList();
    }

    @TestConfiguration
    static class PausingCacheConfig {
        @Bean
        @Primary
        PausingProductCache pausingProductCache(RedisProductCache redisProductCache) {
            return new PausingProductCache(redisProductCache);
        }
    }

    // 다음 상품 캐시 저장 한 번을 멈춰 두었다가 resume()에서 이어서 저장한다. 조회는 그대로 Redis에 위임한다
    static class PausingProductCache implements ProductCache {
        private final ProductCache delegate;
        private volatile CountDownLatch paused;
        private volatile CountDownLatch resumed;

        PausingProductCache(ProductCache delegate) {
            this.delegate = delegate;
        }

        void pauseNextSave() {
            resumed = new CountDownLatch(1);
            paused = new CountDownLatch(1);
        }

        boolean awaitPaused(long timeoutSeconds) throws InterruptedException {
            return paused.await(timeoutSeconds, TimeUnit.SECONDS);
        }

        void resume() {
            CountDownLatch latch = resumed;
            if (latch != null) {
                latch.countDown();
            }
        }

        @Override
        public Map<Long, ProductSnapshot> findAll(Map<Long, Long> revisionsByProductId) {
            return delegate.findAll(revisionsByProductId);
        }

        @Override
        public void saveAll(Map<Long, Long> revisionsByProductId, List<ProductSnapshot> snapshots) {
            CountDownLatch pausedLatch = paused;
            CountDownLatch resumedLatch = resumed;
            if (pausedLatch != null && pausedLatch.getCount() > 0) {
                pausedLatch.countDown();
                try {
                    resumedLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            delegate.saveAll(revisionsByProductId, snapshots);
        }
    }
}

package com.loopers.application.product;

import com.loopers.application.product.cache.ProductListCache;
import com.loopers.application.product.dto.ProductListItemInfo;
import com.loopers.application.product.dto.ProductListQuery;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.common.Money;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.product.Stock;
import com.loopers.support.fixture.ExposedProducts;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * 상품 목록 캐시가 틀릴 수 있는 두 장면을 실제 MySQL·Redis 로 재현하고, 남는 낡음이 허용 범위 안에 있는지 확인한다.
 *
 * <p>TTL 을 3초로 줄여 만료 뒤 복구까지 본다. 낡은 값이 남는 것은 실패가 아니라 허용한 결과이며, 삭제 비노출은 낡음 허용 대상이 아니다.
 */
@SpringBootTest(properties = "product.list-cache.ttl=3s")
class ProductCacheFailureTest {

    private static final long TIMEOUT_SECONDS = 10;

    @MockitoSpyBean
    private ProductListCache productListCache;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private AdminProductFacade adminProductFacade;

    @Autowired
    private ExposedProducts exposedProducts;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    private Brand loopers;
    private Product shoes;
    private Product shirt;
    private Product socks;

    // 루퍼스: 러닝화 30,000 / 티셔츠 20,000 / 양말 10,000
    @BeforeEach
    void setUp() {
        loopers = brandRepository.save(Brand.create("루퍼스"));
        shoes = saveProduct("러닝화", 30_000);
        shirt = saveProduct("티셔츠", 20_000);
        socks = saveProduct("양말", 10_000);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private Product saveProduct(String name, long price) {
        return exposedProducts.save(Product.create(loopers.getId(), name, new Money(price), new Stock(10)));
    }

    private List<Long> priceAscIds() {
        return productFacade.getList(loopers.getId(), "price_asc", 0, 20).getContent().stream()
            .map(ProductListItemInfo::id).toList();
    }

    private long listedPriceOf(Product product) {
        return productFacade.getList(loopers.getId(), "price_asc", 0, 20).getContent().stream()
            .filter(info -> info.id().equals(product.getId()))
            .findFirst().orElseThrow().price();
    }

    private boolean cached() {
        return productListCache.find(new ProductListQuery(loopers.getId(), ProductSort.PRICE_ASC, 0, 20)).isPresent();
    }

    @DisplayName("commit 뒤 무효화가 실패할 때,")
    @Nested
    class EvictionFailure {

        @BeforeEach
        void failEviction() {
            priceAscIds(); // 캐시를 채운다
            doThrow(new IllegalStateException("캐시 비우기 실패 재현")).when(productListCache).evictAll();
        }

        @DisplayName("관리자 수정은 성공하고 DB 에 commit 되며, 옛 가격은 TTL 까지만 남고 만료 뒤 DB 와 같아진다.")
        @Test
        void keepsAdminSuccess_andRecoversAfterTtl() {
            // act
            assertDoesNotThrow(() -> adminProductFacade.update(socks.getId(), "양말", 40_000));

            // assert: DB 는 새 가격, 캐시는 옛 가격(허용한 낡음)
            assertThat(adminProductFacade.get(socks.getId()).price()).isEqualTo(40_000);
            assertThat(listedPriceOf(socks)).as("TTL 안의 낡은 가격").isEqualTo(10_000);

            // assert: TTL 이 지나면 DB 와 같은 가격과 순서
            await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> !cached());
            assertThat(listedPriceOf(socks)).isEqualTo(40_000);
            assertThat(priceAscIds()).containsExactly(shirt.getId(), shoes.getId(), socks.getId());
        }

        @DisplayName("관리자 삭제도 성공하고, 삭제된 상품은 TTL 을 기다리지 않고 바로 빠진다.")
        @Test
        void excludesDeletedProductImmediately() {
            // act
            assertDoesNotThrow(() -> adminProductFacade.delete(shirt.getId()));

            // assert
            assertThat(priceAscIds()).containsExactly(socks.getId(), shoes.getId());
        }
    }

    @DisplayName("이전 조회가 변경 뒤에 늦게 캐시를 채울 때,")
    @Nested
    class LateFill {

        private final CountDownLatch loaded = new CountDownLatch(1);
        private final CountDownLatch proceed = new CountDownLatch(1);
        private final AtomicBoolean holdNextSave = new AtomicBoolean(true);
        private final ExecutorService executor = Executors.newSingleThreadExecutor();

        // 첫 저장만 붙잡는다: DB 에서 옛 값을 읽은 조회가 저장 직전에 멈춰, 그 사이 변경·무효화가 끝난 뒤에 옛 값을 저장한다.
        @BeforeEach
        void holdFirstSave() {
            doAnswer(invocation -> {
                if (holdNextSave.compareAndSet(true, false)) {
                    loaded.countDown();
                    if (!proceed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new AssertionError("제한 시간 안에 진행 신호를 받지 못했다.");
                    }
                }
                return invocation.callRealMethod();
            }).when(productListCache).save(any(), any());
        }

        @AfterEach
        void stopWorker() {
            proceed.countDown();
            executor.shutdownNow();
        }

        // 옛 값을 읽은 조회를 저장 직전에 멈춰 두고 change 를 commit 한 뒤, 조회를 마저 진행시켜 옛 값을 저장하게 한다.
        private void lateFillAround(Runnable change) throws Exception {
            Future<?> staleRead = executor.submit(this::readPriceAsc);
            assertThat(loaded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("옛 값을 읽고 저장 직전에 멈춤").isTrue();
            change.run();
            proceed.countDown();
            staleRead.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        private void readPriceAsc() {
            priceAscIds();
        }

        @DisplayName("변경 전 값이 다시 저장되어 TTL 까지 남고, 만료 뒤 DB 와 같아진다.")
        @Test
        void storesStaleValue_andRecoversAfterTtl() throws Exception {
            // act
            lateFillAround(() -> adminProductFacade.update(socks.getId(), "양말", 40_000));

            // assert: 무효화 뒤에 옛 값이 들어갔다(재현 확인)
            assertThat(listedPriceOf(socks)).as("늦은 채움으로 남은 옛 가격").isEqualTo(10_000);

            // assert: 만료 뒤 복구
            await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> !cached());
            assertThat(listedPriceOf(socks)).isEqualTo(40_000);
        }

        @DisplayName("늦게 저장된 값에 삭제된 상품이 있어도 고객 결과에는 나가지 않는다.")
        @Test
        void excludesDeletedProduct_evenIfStaleValueContainsIt() throws Exception {
            // act
            lateFillAround(() -> adminProductFacade.delete(shirt.getId()));

            // assert
            assertThat(priceAscIds()).containsExactly(socks.getId(), shoes.getId());
        }
    }
}

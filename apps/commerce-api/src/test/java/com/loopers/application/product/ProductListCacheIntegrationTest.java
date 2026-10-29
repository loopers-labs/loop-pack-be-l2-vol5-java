package com.loopers.application.product;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.like.LikeApplicationService;
import com.loopers.application.order.OrderApplicationService;
import com.loopers.application.order.OrderResult;
import com.loopers.application.point.PointApplicationService;
import com.loopers.application.product.port.ProductListCache;
import com.loopers.application.product.port.ProductRepository;
import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.brand.BrandId;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductId;
import com.loopers.infrastructure.product.ProductPersistenceAdapter;
import com.loopers.infrastructure.product.RedisProductListCache;
import com.loopers.support.pg.TestPgFixture;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실제 MySQL·Redis로 목록 cache-aside와 commit 후 무효화(설계 19절)를 확인한다.
// 버전 키는 공용 Redis 컨테이너에서 다른 테스트와 공유하므로 절대값이 아니라 전후 차이로 검증한다.
@SpringBootTest
class ProductListCacheIntegrationTest {
    private static final String VERSION_KEY = "products:list:version";
    private static final long WAIT_SECONDS = 10;

    @Autowired private BrandApplicationService brandApplicationService;
    @Autowired private ProductApplicationService productApplicationService;
    @Autowired private LikeApplicationService likeApplicationService;
    @Autowired private PointApplicationService pointApplicationService;
    @Autowired private OrderApplicationService orderApplicationService;
    @Autowired private TestPgFixture testPgFixture;
    @Autowired private ProbingProductRepository productRepository;
    @Autowired private LatchedProductListCache productListCache;
    @Autowired @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private RedisTemplate<String, String> redisTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleanUp databaseCleanUp;

    private long brandA;
    private long brandB;
    private long first;
    private long second;
    private long other;

    @BeforeEach
    void prepare() {
        testPgFixture.start();
        brandA = brandApplicationService.create("브랜드A").id().value();
        brandB = brandApplicationService.create("브랜드B").id().value();
        first = productApplicationService.create(brandA, "첫 상품", 1000, 10).id();
        second = productApplicationService.create(brandA, "둘째 상품", 2000, 5).id();
        other = productApplicationService.create(brandB, "다른 브랜드 상품", 3000, 7).id();
        productRepository.resetProbes();
    }

    @AfterEach
    void clean() throws InterruptedException {
        testPgFixture.close();
        productRepository.resetProbes();
        productListCache.disarm();
        jdbcTemplate.update("delete from order_items");
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("같은 목록을 두 번 조회하면 두 번째는 캐시에서 읽어 DB 목록 검색 없이 같은 응답을 준다")
    void servesSecondRequestFromCache() {
        List<CustomerProductResult> missed = productApplicationService.search(null, 0, 20, "latest");
        List<CustomerProductResult> hit = productApplicationService.search(null, 0, 20, "latest");

        assertThat(productRepository.searchCalls()).isEqualTo(1);
        assertThat(hit).isEqualTo(missed);
        assertThat(missed).extracting(CustomerProductResult::id).containsExactly(other, second, first);
        assertThat(redisTemplate.hasKey("products:list:v" + version() + ":brand=all:sort=latest:page=0:size=20")).isTrue();
    }

    @Test
    @DisplayName("브랜드 필터 목록은 브랜드 ID가 들어간 키에 따로 캐시된다")
    void cachesBrandFilteredListSeparately() {
        productApplicationService.search(null, 0, 2, "price_asc");
        List<CustomerProductResult> missed = productApplicationService.search(brandA, 0, 2, "price_asc");
        List<CustomerProductResult> hit = productApplicationService.search(brandA, 0, 2, "price_asc");

        assertThat(productRepository.searchCalls()).isEqualTo(2);
        assertThat(hit).isEqualTo(missed);
        assertThat(missed).extracting(CustomerProductResult::id).containsExactly(first, second);
        assertThat(redisTemplate.hasKey("products:list:v" + version() + ":brand=" + brandA + ":sort=price_asc:page=0:size=2"))
            .isTrue();
    }

    @Test
    @DisplayName("캐시 적중이어도 재고는 최신 값이고 무효화 없이 삭제된 상품은 응답에서 빠진다")
    void refreshesStockAndDeletionOnHit() {
        productApplicationService.search(null, 0, 20, "latest");
        long before = version();

        productApplicationService.setStock(first, 3);
        jdbcTemplate.update("update products set deleted = true where id = ?", second);
        List<CustomerProductResult> hit = productApplicationService.search(null, 0, 20, "latest");

        assertThat(productRepository.searchCalls()).isEqualTo(1);
        assertThat(version()).isEqualTo(before);
        assertThat(hit).extracting(CustomerProductResult::id).containsExactly(other, first);
        assertThat(hit).filteredOn(product -> product.id() == first).extracting(CustomerProductResult::stock).containsExactly(3);
    }

    @Test
    @DisplayName("상품을 생성하면 commit 후 버전이 오르고 다음 목록에 새 상품이 보인다")
    void invalidatesOnProductCreate() {
        productApplicationService.search(null, 0, 20, "latest");
        long before = version();

        long created = productApplicationService.create(brandB, "새 상품", 500, 1).id();
        List<CustomerProductResult> found = productApplicationService.search(null, 0, 20, "latest");

        assertThat(version()).isEqualTo(before + 1);
        assertThat(found).extracting(CustomerProductResult::id).containsExactly(created, other, second, first);
    }

    @Test
    @DisplayName("상품 이름과 가격을 바꾸면 commit 후 버전이 오르고 다음 목록이 새 이름·가격·순서를 보인다")
    void invalidatesOnProductChange() {
        productApplicationService.search(null, 0, 20, "price_asc");
        long before = version();

        productApplicationService.change(other, "싸진 상품", 100);
        List<CustomerProductResult> found = productApplicationService.search(null, 0, 20, "price_asc");

        assertThat(version()).isEqualTo(before + 1);
        assertThat(found).extracting(CustomerProductResult::id).containsExactly(other, first, second);
        assertThat(found.get(0).name()).isEqualTo("싸진 상품");
        assertThat(found.get(0).price()).isEqualTo(100);
    }

    @Test
    @DisplayName("상품을 삭제하면 commit 후 버전이 오르고 다음 목록에서 빠진다")
    void invalidatesOnProductDelete() {
        productApplicationService.search(null, 0, 20, "latest");
        long before = version();

        productApplicationService.delete(second);
        List<CustomerProductResult> found = productApplicationService.search(null, 0, 20, "latest");

        assertThat(version()).isEqualTo(before + 1);
        assertThat(productRepository.searchCalls()).isEqualTo(2);
        assertThat(found).extracting(CustomerProductResult::id).containsExactly(other, first);
    }

    @Test
    @DisplayName("브랜드 이름을 바꾸면 commit 후 버전이 오르고 다음 목록이 새 브랜드 이름을 보인다")
    void invalidatesOnBrandRename() {
        productApplicationService.search(null, 0, 20, "latest");
        long before = version();

        brandApplicationService.change(brandA, "새 브랜드A");
        List<CustomerProductResult> found = productApplicationService.search(null, 0, 20, "latest");

        assertThat(version()).isEqualTo(before + 1);
        assertThat(found).filteredOn(product -> product.brandId() == brandA)
            .extracting(CustomerProductResult::brandName).containsOnly("새 브랜드A");
    }

    @Test
    @DisplayName("브랜드를 일괄 삭제하면 commit 후 버전이 오르고 다음 목록에서 그 브랜드 상품이 빠진다")
    void invalidatesOnBrandDelete() {
        productApplicationService.search(null, 0, 20, "latest");
        long before = version();

        brandApplicationService.delete(brandA);
        List<CustomerProductResult> found = productApplicationService.search(null, 0, 20, "latest");

        assertThat(version()).isEqualTo(before + 1);
        assertThat(productRepository.searchCalls()).isEqualTo(2);
        assertThat(found).extracting(CustomerProductResult::id).containsExactly(other);
    }

    @Test
    @DisplayName("재고 설정·주문 확정·좋아요 등록과 취소는 버전을 올리지 않고 적중한 목록의 재고는 최신이다")
    void keepsVersionOnStockOrderAndLike() {
        productApplicationService.search(null, 0, 20, "latest");
        long before = version();

        productApplicationService.setStock(first, 8);
        pointApplicationService.charge(1, 10000);
        OrderResult draft = orderApplicationService.create(1, List.of(new OrderApplicationService.ItemRequest(first, 2)));
        orderApplicationService.confirm(1, draft.id(), TestPgFixture.command());
        likeApplicationService.register(1, second);
        likeApplicationService.register(2, second);
        likeApplicationService.cancel(2, second);
        List<CustomerProductResult> hit = productApplicationService.search(null, 0, 20, "latest");

        assertThat(version()).isEqualTo(before);
        assertThat(productRepository.searchCalls()).isEqualTo(1);
        assertThat(hit).filteredOn(product -> product.id() == first).extracting(CustomerProductResult::stock).containsExactly(6);
    }

    @Test
    @DisplayName("브랜드 일괄 삭제가 중간에 실패해 롤백되면 commit 전에도 후에도 버전이 오르지 않는다")
    void keepsVersionOnRollback() {
        long before = version();
        productRepository.failOnSave(2, () -> version());

        assertThatThrownBy(() -> brandApplicationService.delete(brandA))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(ProbingProductRepository.INJECTED_FAILURE);

        assertThat(productRepository.versionAtFailure()).isEqualTo(before);
        assertThat(version()).isEqualTo(before);
        assertThat(productApplicationService.search(brandA, 0, 20, "latest")).hasSize(2);
    }

    @Test
    @DisplayName("commit 전 값을 읽은 요청이 무효화 뒤 늦게 캐시에 써도 이후 요청은 새 가격을 본다")
    void ignoresLateFillAfterInvalidation() throws Exception {
        long before = version();
        productListCache.armSaveLatch();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<List<CustomerProductResult>> slow = executor.submit(
                () -> productApplicationService.search(null, 0, 20, "price_asc"));
            assertThat(productListCache.awaitSaveArrival()).isTrue();

            productApplicationService.change(first, "첫 상품", 9000);
            productListCache.releaseSave();
            List<CustomerProductResult> slowResult = slow.get(WAIT_SECONDS, TimeUnit.SECONDS);

            List<CustomerProductResult> found = productApplicationService.search(null, 0, 20, "price_asc");

            assertThat(slowResult).filteredOn(product -> product.id() == first)
                .extracting(CustomerProductResult::price).containsExactly(1000L);
            assertThat(productListCache.lateSaveVersion()).isEqualTo(before);
            assertThat(version()).isEqualTo(before + 1);
            assertThat(found).extracting(CustomerProductResult::id).containsExactly(second, other, first);
            assertThat(found).filteredOn(product -> product.id() == first)
                .extracting(CustomerProductResult::price).containsExactly(9000L);
        } finally {
            productListCache.releaseSave();
            executor.shutdownNow();
            executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private long version() {
        String value = redisTemplate.opsForValue().get(VERSION_KEY);
        return value == null ? 0 : Long.parseLong(value);
    }

    @TestConfiguration
    static class Probes {
        @Bean
        @Primary
        ProbingProductRepository probingProductRepository(ProductPersistenceAdapter productPersistenceAdapter,
            EntityManager entityManager) {
            return new ProbingProductRepository(productPersistenceAdapter, entityManager);
        }

        @Bean
        @Primary
        LatchedProductListCache latchedProductListCache(RedisProductListCache redisProductListCache) {
            return new LatchedProductListCache(redisProductListCache);
        }
    }

    // 실제 어댑터에 위임하며 목록 검색 횟수를 세고, 지정한 순번의 저장에서 앞선 변경을 DB로 보낸 뒤 실패를 일으킨다.
    static class ProbingProductRepository implements ProductRepository {
        static final String INJECTED_FAILURE = "주입된 저장 실패";

        private final ProductRepository delegate;
        private final EntityManager entityManager;
        private final AtomicInteger searchCalls = new AtomicInteger();
        private final AtomicInteger failOnSave = new AtomicInteger();
        private final AtomicInteger saveCount = new AtomicInteger();
        private final AtomicLong versionAtFailure = new AtomicLong(-1);
        private volatile LongSupplier versionReader = () -> -1;

        ProbingProductRepository(ProductRepository delegate, EntityManager entityManager) {
            this.delegate = delegate;
            this.entityManager = entityManager;
        }

        void resetProbes() {
            searchCalls.set(0);
            failOnSave.set(0);
            saveCount.set(0);
            versionAtFailure.set(-1);
        }

        void failOnSave(int saveNumber, LongSupplier versionReader) {
            this.versionReader = versionReader;
            saveCount.set(0);
            failOnSave.set(saveNumber);
        }

        int searchCalls() {
            return searchCalls.get();
        }

        long versionAtFailure() {
            return versionAtFailure.get();
        }

        @Override
        public List<Product> search(Long brandId, int page, int size, String sort) {
            searchCalls.incrementAndGet();
            return delegate.search(brandId, page, size, sort);
        }

        @Override
        public Product save(Product product) {
            if (failOnSave.get() > 0 && saveCount.incrementAndGet() == failOnSave.get()) {
                entityManager.flush();
                versionAtFailure.set(versionReader.getAsLong());
                throw new IllegalStateException(INJECTED_FAILURE);
            }
            return delegate.save(product);
        }

        @Override
        public List<Product> findPage(int page, int size) {
            return delegate.findPage(page, size);
        }

        @Override
        public List<Product> findAllByIds(Collection<ProductId> ids) {
            return delegate.findAllByIds(ids);
        }

        @Override
        public Optional<Product> findById(ProductId id) {
            return delegate.findById(id);
        }

        @Override
        public Optional<Product> findByIdForUpdate(ProductId id) {
            return delegate.findByIdForUpdate(id);
        }

        @Override
        public List<Product> findActiveByBrandIdForUpdate(BrandId brandId) {
            return delegate.findActiveByBrandIdForUpdate(brandId);
        }

        @Override
        public Map<ProductId, Long> findLikeCounts(Collection<ProductId> ids) {
            return delegate.findLikeCounts(ids);
        }

        @Override
        public void adjustLikeCount(ProductId id, long delta) {
            delegate.adjustLikeCount(id, delta);
        }
    }

    // 실제 Redis 구현에 위임하되, 준비해 두면 다음 한 번의 캐시 쓰기를 래치로 멈춰 늦은 채움 순서를 만든다.
    static class LatchedProductListCache implements ProductListCache {
        private final ProductListCache delegate;
        private final AtomicBoolean armed = new AtomicBoolean();
        private final AtomicLong lateSaveVersion = new AtomicLong(-1);
        private volatile CountDownLatch arrived = new CountDownLatch(0);
        private volatile CountDownLatch release = new CountDownLatch(0);

        LatchedProductListCache(ProductListCache delegate) {
            this.delegate = delegate;
        }

        void armSaveLatch() {
            arrived = new CountDownLatch(1);
            release = new CountDownLatch(1);
            lateSaveVersion.set(-1);
            armed.set(true);
        }

        boolean awaitSaveArrival() throws InterruptedException {
            return arrived.await(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        void releaseSave() {
            release.countDown();
        }

        void disarm() {
            armed.set(false);
            release.countDown();
        }

        long lateSaveVersion() {
            return lateSaveVersion.get();
        }

        @Override
        public OptionalLong currentVersion() {
            return delegate.currentVersion();
        }

        @Override
        public Optional<List<ProductListItem>> find(long version, ProductListQuery query) {
            return delegate.find(version, query);
        }

        @Override
        public void save(long version, ProductListQuery query, List<ProductListItem> items) {
            if (armed.compareAndSet(true, false)) {
                lateSaveVersion.set(version);
                arrived.countDown();
                try {
                    if (!release.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("늦은 채움 해제 신호를 기다리지 못했습니다.");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("늦은 채움 대기 중 중단되었습니다.", e);
                }
            }
            delegate.save(version, query, items);
        }

        @Override
        public void invalidate() {
            delegate.invalidate();
        }
    }
}

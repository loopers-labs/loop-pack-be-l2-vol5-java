package com.loopers.application.product;

import com.loopers.config.redis.RedisConfig;
import com.loopers.config.redis.RedisProperties;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductSort;
import com.loopers.infrastructure.product.RedisProductListCache;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ProductListCacheFailureIntegrationTest.FaultInjectionConfig.class)
@DisplayName("상품 목록 캐시 장애 장면")
class ProductListCacheFailureIntegrationTest {

    @Autowired
    private FaultInjectingProductListCache faultInjectingCache;

    @Autowired
    private CustomerProductListReader customerProductListReader;

    @Autowired
    private RedisProperties redisProperties;

    @Autowired
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @PersistenceContext
    private EntityManager entityManager;

    private Statistics statistics;
    private boolean statisticsWereEnabled;

    private Brand brand;
    private Product deletedLater;
    private Product remaining;

    @BeforeEach
    void setUp() {
        redisCleanUp.truncateAll();
        statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statisticsWereEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        brand = brandRepository.save(Brand.create("Nike"));
        deletedLater = productRepository.save(Product.create(brand.getId(), "Air", 1_000L));
        remaining = productRepository.save(Product.create(brand.getId(), "Max", 2_000L));
    }

    @AfterEach
    void tearDown() {
        faultInjectingCache.recover();
        statistics.setStatisticsEnabled(statisticsWereEnabled);
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    @Test
    @DisplayName("상품 삭제 뒤 무효화가 누락돼도 삭제된 상품을 캐시에서 반환하지 않는다.")
    void hidesDeletedProduct_whenInvalidationIsMissed() {
        warmUpBrandAndAllLists();

        softDeleteProductsWithoutInvalidation("id = ?", deletedLater.getId());

        assertThat(brandList()).extracting(CustomerProductInfo::id).containsExactly(remaining.getId());
        assertThat(allList()).extracting(CustomerProductInfo::id).containsExactly(remaining.getId());
    }

    @Test
    @DisplayName("브랜드 삭제 뒤 무효화가 누락돼도 연결 상품을 캐시에서 반환하지 않는다.")
    void hidesBrandProducts_whenBrandDeleteInvalidationIsMissed() {
        warmUpBrandAndAllLists();

        jdbcTemplate.update("UPDATE brands SET deleted_at = NOW(6) WHERE id = ?", brand.getId());
        softDeleteProductsWithoutInvalidation("brand_id = ?", brand.getId());

        assertThat(brandList()).isEmpty();
        assertThat(allList()).isEmpty();
    }

    @Test
    @DisplayName("삭제된 상품을 발견하면 DB 결과로 캐시를 다시 채워 다음 hit에서 사용한다.")
    void refillsCache_afterDetectingDeletedProduct() {
        brandList();
        softDeleteProductsWithoutInvalidation("id = ?", deletedLater.getId());
        brandList();
        statistics.clear();

        List<CustomerProductInfo> results = brandList();

        assertThat(results).extracting(CustomerProductInfo::id).containsExactly(remaining.getId());
        assertThat(statistics.getPrepareStatementCount())
            .as("삭제 확인 1회만 실행한다.")
            .isEqualTo(1L);
    }

    @Test
    @DisplayName("commerce-api는 Redis 연결·명령 대기를 각각 100ms로 제한한다.")
    void limitsRedisWaitTo100Millis() {
        assertThat(redisProperties.connectTimeout()).isEqualTo(Duration.ofMillis(100));
        assertThat(redisProperties.commandTimeout()).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    @DisplayName("F1: 버전을 읽지 못하면 DB 결과를 반환하고 캐시에 저장하지 않는다.")
    void returnsDbResultWithoutCaching_whenVersionReadFails() {
        faultInjectingCache.failVersionRead = true;

        List<CustomerProductInfo> results = brandList();

        assertThat(results).extracting(CustomerProductInfo::id).containsExactly(remaining.getId(), deletedLater.getId());
        assertThat(redisTemplate.keys("product:list:v1:*")).isEmpty();
    }

    @Test
    @DisplayName("F1: 캐시를 읽지 못하면 DB 결과를 반환한다.")
    void returnsDbResult_whenCacheReadFails() {
        brandList();
        jdbcTemplate.update("UPDATE products SET price = ? WHERE id = ?", 9_000L, remaining.getId());
        faultInjectingCache.failFind = true;

        List<CustomerProductInfo> results = brandList();

        assertThat(results).filteredOn(info -> info.id().equals(remaining.getId()))
            .extracting(CustomerProductInfo::price).containsExactly(9_000L);
    }

    @Test
    @DisplayName("F2: 캐시 저장에 실패해도 DB 결과를 반환한다.")
    void returnsDbResult_whenCacheWriteFails() {
        faultInjectingCache.failPut = true;

        List<CustomerProductInfo> results = brandList();

        assertThat(results).extracting(CustomerProductInfo::id).containsExactly(remaining.getId(), deletedLater.getId());
        assertThat(redisTemplate.keys("product:list:v1:*")).isEmpty();
    }

    @Test
    @DisplayName("F3: 커밋 뒤 무효화에 실패해도 커밋은 유지되고, 이전 표시 값은 만료 뒤 DB 결과로 복구된다.")
    void keepsCommitAndRecoversAfterExpiry_whenInvalidationFails() {
        brandList();
        faultInjectingCache.failInvalidate = true;

        productFacade.update(remaining.getId(), "Max 2", 9_000L);

        assertThat(productRepository.findById(remaining.getId()).orElseThrow().getPrice()).isEqualTo(9_000L);
        assertThat(findIn(brandList(), remaining.getId()).price())
            .as("무효화 실패 시 이전 표시 가격을 허용한다.")
            .isEqualTo(2_000L);

        expireCachedLists();

        assertThat(findIn(brandList(), remaining.getId()))
            .extracting(CustomerProductInfo::name, CustomerProductInfo::price)
            .containsExactly("Max 2", 9_000L);
    }

    @Test
    @DisplayName("F4: 변경 전에 시작한 조회가 늦게 저장해도 변경 뒤 시작한 조회는 변경된 결과를 반환한다.")
    void returnsChangedResult_whenEarlierReaderStoresLate() {
        long versionReadBeforeWrite = faultInjectingCache.currentVersion(brand.getId());
        List<CustomerProductInfo> staleResult = customerProductListReader.read(brand.getId(), ProductSort.LATEST, 0, 20);

        productFacade.update(remaining.getId(), "Max 2", 9_000L);
        faultInjectingCache.put(
            new ProductListCacheKey(brand.getId(), ProductSort.LATEST, 0, 20, versionReadBeforeWrite),
            staleResult
        );

        assertThat(findIn(brandList(), remaining.getId()))
            .extracting(CustomerProductInfo::name, CustomerProductInfo::price)
            .containsExactly("Max 2", 9_000L);
    }

    private void expireCachedLists() {
        redisTemplate.delete(redisTemplate.keys("product:list:v1:*"));
    }

    private CustomerProductInfo findIn(List<CustomerProductInfo> products, Long productId) {
        return products.stream().filter(info -> info.id().equals(productId)).findFirst().orElseThrow();
    }

    private void softDeleteProductsWithoutInvalidation(String condition, Long id) {
        jdbcTemplate.update("UPDATE products SET deleted_at = NOW(6) WHERE " + condition, id);
    }

    private void warmUpBrandAndAllLists() {
        brandList();
        allList();
    }

    private List<CustomerProductInfo> brandList() {
        return productFacade.getCustomerList(brand.getId(), 0, 20, "latest");
    }

    private List<CustomerProductInfo> allList() {
        return productFacade.getCustomerList(null, 0, 20, "latest");
    }

    @TestConfiguration
    static class FaultInjectionConfig {

        @Bean
        @Primary
        FaultInjectingProductListCache faultInjectingProductListCache(RedisProductListCache redisProductListCache) {
            return new FaultInjectingProductListCache(redisProductListCache);
        }
    }

    static class FaultInjectingProductListCache implements ProductListCache {

        private final ProductListCache delegate;
        volatile boolean failVersionRead;
        volatile boolean failFind;
        volatile boolean failPut;
        volatile boolean failInvalidate;

        FaultInjectingProductListCache(ProductListCache delegate) {
            this.delegate = delegate;
        }

        void recover() {
            failVersionRead = false;
            failFind = false;
            failPut = false;
            failInvalidate = false;
        }

        @Override
        public long currentVersion(Long brandId) {
            failIf(failVersionRead);
            return delegate.currentVersion(brandId);
        }

        @Override
        public Optional<List<CustomerProductInfo>> find(ProductListCacheKey key) {
            failIf(failFind);
            return delegate.find(key);
        }

        @Override
        public void put(ProductListCacheKey key, List<CustomerProductInfo> products) {
            failIf(failPut);
            delegate.put(key, products);
        }

        @Override
        public void invalidate(Long brandId) {
            failIf(failInvalidate);
            delegate.invalidate(brandId);
        }

        private void failIf(boolean injected) {
            if (injected) {
                throw new RedisConnectionFailureException("주입한 Redis 장애");
            }
        }
    }
}

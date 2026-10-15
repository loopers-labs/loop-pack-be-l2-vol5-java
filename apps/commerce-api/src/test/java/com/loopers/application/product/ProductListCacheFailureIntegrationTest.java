package com.loopers.application.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DisplayName("상품 목록 캐시 장애 장면")
class ProductListCacheFailureIntegrationTest {

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
}

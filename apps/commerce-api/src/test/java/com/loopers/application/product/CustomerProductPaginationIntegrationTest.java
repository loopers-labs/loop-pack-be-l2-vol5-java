package com.loopers.application.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.like.Like;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.product.ProductJpaEntity;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@DisplayName("고객 상품 목록 DB 페이지 조회")
class CustomerProductPaginationIntegrationTest {

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private LikeRepository likeRepository;

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

    @BeforeEach
    void enableStatistics() {
        statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statisticsWereEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
    }

    @AfterEach
    void tearDown() {
        statistics.setStatisticsEnabled(statisticsWereEnabled);
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    @ParameterizedTest(name = "정렬={0}, 브랜드 필터={1}")
    @CsvSource({
        "latest, true, 3, 2",
        "latest, false, 4, 3",
        "price_asc, true, 2, 5",
        "price_asc, false, 3, 2",
        "likes_desc, true, 3, 1",
        "likes_desc, false, 2, 3"
    })
    @DisplayName("전체 후보의 정렬과 삭제 조건을 적용한 뒤 요청 페이지의 상품 두 개만 DB에서 불러온다.")
    void loadsOnlyRequestedPageAfterFilteringAndSorting(
        String sort, boolean filterByBrand, int firstIndex, int secondIndex
    ) {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Brand otherBrand = brandRepository.save(Brand.create("Adidas"));
        long[] prices = {100L, 300L, 200L, 100L, 400L, 200L};
        int[] likeCounts = {3, 1, 2, 1, 0, 0, 4};
        List<Product> products = new ArrayList<>();
        for (int index = 0; index < prices.length; index++) {
            products.add(productRepository.save(Product.create(brand.getId(), "Product " + index, prices[index])));
        }
        products.add(productRepository.save(Product.create(otherBrand.getId(), "Other brand", 50L)));
        for (int index = 0; index < products.size(); index++) {
            addLikes(products.get(index), likeCounts[index]);
        }
        Product deleted = productRepository.save(Product.create(brand.getId(), "Deleted", 1L));
        addLikes(deleted, 5);
        productRepository.softDeleteActiveById(deleted.getId(), ZonedDateTime.now());
        entityManager.flush();
        Timestamp sameCreatedAt = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        for (Product product : products) {
            jdbcTemplate.update("UPDATE products SET created_at = ? WHERE id = ?", sameCreatedAt, product.getId());
        }
        entityManager.clear();
        statistics.clear();

        List<CustomerProductInfo> results = productFacade.getCustomerList(
            filterByBrand ? brand.getId() : null, 1, 2, sort
        );

        assertThat(results).extracting(CustomerProductInfo::id)
            .containsExactly(products.get(firstIndex).getId(), products.get(secondIndex).getId());
        assertThat(results).extracting(CustomerProductInfo::price)
            .containsExactly(products.get(firstIndex).getPrice(), products.get(secondIndex).getPrice());
        assertThat(results).extracting(CustomerProductInfo::likeCount)
            .containsExactly((long) likeCounts[firstIndex], (long) likeCounts[secondIndex]);
        assertThat(statistics.getEntityStatistics(ProductJpaEntity.class.getName()).getLoadCount())
            .as("응답 페이지 밖의 상품을 애플리케이션으로 가져오지 않는다.")
            .isEqualTo(2L);
        assertThat(statistics.getPrepareStatementCount())
            .as("상품 페이지, 브랜드 일괄 조회, 좋아요 일괄 집계만 실행한다.")
            .isEqualTo(3L);
    }

    @ParameterizedTest(name = "정렬={0}")
    @ValueSource(strings = {"latest", "price_asc", "likes_desc"})
    @DisplayName("빈 페이지는 상품 조회만 수행하고 브랜드와 좋아요를 조회하지 않는다.")
    void returnsEmptyPageWithoutBrandOrLikeQueries(String sort) {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        productRepository.save(Product.create(brand.getId(), "Product", 100L));
        entityManager.flush();
        entityManager.clear();
        statistics.clear();

        List<CustomerProductInfo> results = productFacade.getCustomerList(brand.getId(), 1, 20, sort);

        assertThat(results).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1L);
    }

    @ParameterizedTest(name = "정렬={0}")
    @ValueSource(strings = {"latest", "price_asc", "likes_desc"})
    @DisplayName("정수 범위를 넘는 페이지 offset은 상품을 불러오지 않고 빈 목록을 반환한다.")
    void returnsEmptyPageWithoutLoadingProductsForOffsetBeyondIntegerRange(String sort) {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        productRepository.save(Product.create(brand.getId(), "Product", 100L));
        entityManager.flush();
        entityManager.clear();
        statistics.clear();

        List<CustomerProductInfo> results = productFacade.getCustomerList(brand.getId(), Integer.MAX_VALUE, 100, sort);

        assertThat(results).isEmpty();
        assertThat(statistics.getEntityStatistics(ProductJpaEntity.class.getName()).getLoadCount()).isZero();
    }

    private void addLikes(Product product, int count) {
        for (long userId = 1; userId <= count; userId++) {
            likeRepository.save(Like.create(userId, product.getId()));
        }
    }
}

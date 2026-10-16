package com.loopers.application.product;

import com.loopers.application.like.LikeFacade;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductSort;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 상품 목록 조회 경로에 붙은 캐시를 실제 DB · Redis 로 확인함 (4주차 docs/week4/cache-design.md 2.1, 4.1).
 * SQL 수는 Hibernate 가 준비한 문장 수로 센다
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ProductFacadeCacheTest {

    private static final Pageable FIRST_PAGE = PageRequest.of(0, ProductFacade.CACHEABLE_SIZE);

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private ProductService productService;

    @Autowired
    private LikeFacade likeFacade;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    private Long brandId;

    /** 브랜드 하나에 첫 페이지를 넘는 25개, 다른 브랜드에 3개. 가격 · 재고 · 좋아요가 섞이게 둔다 */
    @BeforeEach
    void setUp() {
        brandId = createBrandWithProducts("브랜드", 25);
        createBrandWithProducts("다른 브랜드", 3);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private Long createBrandWithProducts(String name, int count) {
        Brand brand = transactionTemplate.execute(status -> {
            Brand created = new Brand(name, name + " 설명");
            entityManager.persist(created);
            return created;
        });
        for (int i = 0; i < count; i++) {
            Product product = productService.create(brand, name + " 상품" + i, 1_000L * ((i * 7) % 10 + 1));
            productService.changeStock(product.getId(), i % 3);
            for (long userId = 1; userId <= i % 4; userId++) {
                likeFacade.like(userId, product.getId());
            }
        }
        return brand.getId();
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    /** 이 조회가 준비한 SQL 문장 수 */
    private long statementsOf(Runnable query) {
        statistics().clear();
        query.run();
        return statistics().getPrepareStatementCount();
    }

    private Page<ProductInfo> dbOnly(Long brand, ProductSort sort, Pageable pageable) {
        return productService.getActiveProductViews(brand, sort, pageable).map(ProductInfo::from);
    }

    @DisplayName("첫 페이지 · 기본 크기는 DB 만 읽은 결과 · 처음(miss) · 다음(hit) 결과가 모두 같고, hit 에서는 SQL 을 실행하지 않는다.")
    @ParameterizedTest(name = "브랜드 필터 {0} · {1}")
    @CsvSource({"true, LATEST", "true, PRICE_ASC", "true, LIKES_DESC", "false, LATEST", "false, PRICE_ASC", "false, LIKES_DESC"})
    void returnsSameResult_andSkipsSql_onHit(boolean filterByBrand, ProductSort sort) {
        // arrange
        Long brand = filterByBrand ? brandId : null;
        Page<ProductInfo> expected = dbOnly(brand, sort, FIRST_PAGE);

        // act
        Page<ProductInfo>[] results = new Page[2];
        long missStatements = statementsOf(() -> results[0] = productFacade.getProducts(brand, sort, FIRST_PAGE));
        long hitStatements = statementsOf(() -> results[1] = productFacade.getProducts(brand, sort, FIRST_PAGE));

        // assert
        assertAll(
            () -> assertThat(results[0].getContent()).isEqualTo(expected.getContent()),
            () -> assertThat(results[1].getContent()).isEqualTo(expected.getContent()),
            () -> assertThat(results[1].getTotalElements()).isEqualTo(expected.getTotalElements()),
            () -> assertThat(results[1].getTotalPages()).isEqualTo(expected.getTotalPages()),
            () -> assertThat(results[1].getNumber()).isEqualTo(expected.getNumber()),
            () -> assertThat(results[1].getSize()).isEqualTo(expected.getSize()),
            () -> assertThat(missStatements).isEqualTo(2),
            () -> assertThat(hitStatements).isZero()
        );
    }

    @DisplayName("첫 페이지가 아니면, 캐시를 거치지 않고 매번 DB 를 읽는다. (2.1)")
    @Test
    void readsDatabaseEveryTime_whenNotFirstPage() {
        // arrange
        Pageable secondPage = PageRequest.of(1, ProductFacade.CACHEABLE_SIZE);
        Page<ProductInfo> expected = dbOnly(brandId, ProductSort.LATEST, secondPage);

        // act
        Page<ProductInfo>[] result = new Page[1];
        long first = statementsOf(() -> productFacade.getProducts(brandId, ProductSort.LATEST, secondPage));
        long second = statementsOf(() -> result[0] = productFacade.getProducts(brandId, ProductSort.LATEST, secondPage));

        // assert
        assertAll(
            () -> assertThat(first).isEqualTo(2),
            () -> assertThat(second).isEqualTo(2),
            () -> assertThat(result[0].getContent()).isEqualTo(expected.getContent())
        );
    }

    @DisplayName("기본 크기가 아니면, 첫 페이지여도 캐시를 거치지 않는다. (2.1)")
    @Test
    void readsDatabaseEveryTime_whenNotDefaultSize() {
        // arrange
        Pageable smallerFirstPage = PageRequest.of(0, 10);

        // act
        long first = statementsOf(() -> productFacade.getProducts(brandId, ProductSort.LATEST, smallerFirstPage));
        long second = statementsOf(() -> productFacade.getProducts(brandId, ProductSort.LATEST, smallerFirstPage));

        // assert
        assertAll(
            () -> assertThat(first).isEqualTo(2),
            () -> assertThat(second).isEqualTo(2)
        );
    }
}

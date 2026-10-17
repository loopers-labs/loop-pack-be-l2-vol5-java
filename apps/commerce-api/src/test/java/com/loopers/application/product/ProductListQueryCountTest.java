package com.loopers.application.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.common.Money;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.Stock;
import com.loopers.support.fixture.ExposedProducts;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 고객 상품 목록이 상품 수와 상관없이 정해진 횟수의 SQL 로 조회하는지(N+1 이 없는지) 실행된 SQL 문 수로 확인한다.
 * 통계는 이 클래스에서만 켠다. 문 수는 설계 문서(week4 product-query-design.md D-34)의 값이다.
 *
 * <p>총 개수 쿼리까지 실행되도록 페이지가 가득 차는 상품 수를 쓴다.
 * 일반: 상품 페이지 1 + 총 개수 1 + 브랜드 1 + 좋아요 수 1 = 4.
 * 브랜드 없는 {@code likes_desc}: 집계에서 상품 id 1 + 총 개수 1 + 상품 1 + 브랜드 1 + 좋아요 수 1 = 5.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ProductListQueryCountTest {

    @Autowired
    private ExposedProducts exposedProducts;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private LikeService likeService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    // 브랜드 2개, 첫 브랜드에 productCount 개 상품(좋아요 일부)을 만든다. 반환값은 첫 브랜드 id.
    private Long prepare(int productCount) {
        Brand brand = brandRepository.save(Brand.create("루퍼스"));
        Brand other = brandRepository.save(Brand.create("다른 브랜드"));
        for (int i = 0; i < productCount; i++) {
            Product product = exposedProducts.save(
                Product.create(brand.getId(), "상품" + i, new Money(1_000L + i), new Stock(10)));
            for (long userId = 1; userId <= i % 4; userId++) {
                likeService.like(userId, product.getId());
            }
        }
        exposedProducts.save(Product.create(other.getId(), "다른 브랜드 상품", new Money(1_000), new Stock(10)));
        return brand.getId();
    }

    private Statistics statistics() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        return statistics;
    }

    @DisplayName("상품 수와 상관없이 정해진 횟수의 SQL 로 목록을 조회한다.")
    @ParameterizedTest(name = "상품 {0}개, sort={1}, 브랜드 지정={2} -> SQL {3}개")
    @CsvSource({
        "25, latest, false, 4",
        "45, latest, false, 4",
        "25, price_asc, true, 4",
        "45, price_asc, true, 4",
        "25, likes_desc, true, 4",
        "45, likes_desc, true, 4",
        "25, likes_desc, false, 5",
        "45, likes_desc, false, 5"
    })
    void queriesFixedNumberOfStatements(int productCount, String sort, boolean withBrand, int expectedStatements) {
        // arrange
        Long brandId = prepare(productCount);
        Statistics statistics = statistics();

        // act
        var page = productFacade.getList(withBrand ? brandId : null, sort, 0, 20);

        // assert
        assertThat(page.getContent()).hasSize(20);
        assertThat(statistics.getPrepareStatementCount()).as("실행된 SQL 수").isEqualTo(expectedStatements);
    }
}

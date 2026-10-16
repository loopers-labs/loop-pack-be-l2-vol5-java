package com.loopers.application.product;

import com.loopers.domain.product.CachedProductItem;
import com.loopers.domain.product.ProductListCache;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.SqlCounter;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 캐시를 거치지 않은(DB-only) 상품 목록 결과. 테스트 설정에서만 ProductListCache 를
 * "find 는 항상 empty, save·evict 는 무동작" 구현으로 바꿔 Facade 가 항상 DB 경로를 타게 한다.
 * 운영 코드에는 캐시 우회 경로가 없다.
 */
@SpringBootTest
@Sql("/sql/product-perf-seed.sql")
class ProductQueryWithoutCacheTest {

    @TestConfiguration
    static class NoCacheConfig {
        @Primary
        @Bean
        ProductListCache noOpProductListCache() {
            return new ProductListCache() {
                @Override
                public Optional<List<CachedProductItem>> find(Long brandId, int page) {
                    return Optional.empty();
                }

                @Override
                public void save(Long brandId, int page, List<CachedProductItem> items) {
                }

                @Override
                public void evictBrandAfterCommit(Long brandId) {
                }
            };
        }
    }

    private final ProductFacade productFacade;
    private final EntityManagerFactory entityManagerFactory;
    private final DatabaseCleanUp databaseCleanUp;

    private SqlCounter sqlCounter;

    @Autowired
    public ProductQueryWithoutCacheTest(
        ProductFacade productFacade,
        EntityManagerFactory entityManagerFactory,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.productFacade = productFacade;
        this.entityManagerFactory = entityManagerFactory;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        sqlCounter = new SqlCounter(entityManagerFactory);
    }

    @AfterEach
    void tearDown() {
        sqlCounter.disable();
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("캐시 없이 조회한 브랜드 7 가격순 첫 페이지는 고정 기대값과 같고, SQL 3개(상품·좋아요 집계·브랜드)로 끝난다.")
    @Test
    void matchesFixture_withThreeStatements() {
        // arrange
        sqlCounter.reset();

        // act
        List<ProductInfo> result = productFacade.getProducts(
            ProductQueryFixture.BRAND_ID, ProductQueryFixture.SORT, ProductQueryFixture.PAGE, ProductQueryFixture.SIZE);

        // assert
        assertThat(result).containsExactlyElementsOf(ProductQueryFixture.EXPECTED_PAGE_0);
        assertThat(sqlCounter.count()).isEqualTo(3);
    }
}

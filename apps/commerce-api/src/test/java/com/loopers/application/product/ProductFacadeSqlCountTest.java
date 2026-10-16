package com.loopers.application.product;

import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import com.loopers.utils.SqlCounter;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상품 목록 1회 조회가 내는 SQL 수를 센다. 데이터는 성능 측정 seed 를 그대로 쓴다
 * (brands 100, products 10000: 브랜드 7 은 100개, 필터 없는 가격순 1페이지는 브랜드 13개).
 */
@SpringBootTest
@Sql("/sql/product-perf-seed.sql")
class ProductFacadeSqlCountTest {

    private final ProductFacade productFacade;
    private final EntityManagerFactory entityManagerFactory;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;

    private SqlCounter sqlCounter;

    @Autowired
    public ProductFacadeSqlCountTest(
        ProductFacade productFacade,
        EntityManagerFactory entityManagerFactory,
        DatabaseCleanUp databaseCleanUp,
        RedisCleanUp redisCleanUp
    ) {
        this.productFacade = productFacade;
        this.entityManagerFactory = entityManagerFactory;
        this.databaseCleanUp = databaseCleanUp;
        this.redisCleanUp = redisCleanUp;
    }

    @BeforeEach
    void setUp() {
        sqlCounter = new SqlCounter(entityManagerFactory);
    }

    @AfterEach
    void tearDown() {
        sqlCounter.disable();
        databaseCleanUp.truncateAllTables();
        // 브랜드 필터 + 가격순 첫 페이지 호출은 캐시 키를 남기므로 다른 테스트에 새지 않게 지운다
        redisCleanUp.truncateAll();
    }

    @DisplayName("고객이 상품 목록을 조회할 때, ")
    @Nested
    class GetProductsAsCustomer {
        @DisplayName("브랜드 필터 + 가격순 1페이지(20개)는 상품 목록·좋아요 집계·브랜드 조회 SQL 3개로 끝난다.")
        @Test
        void executesThreeStatements_whenFilteredByBrand() {
            // arrange
            sqlCounter.reset();

            // act
            List<ProductInfo> result = productFacade.getProducts(7L, "price_asc", 0, 20);

            // assert
            assertThat(result).hasSize(20);
            assertThat(sqlCounter.count()).isEqualTo(3);
        }

        @DisplayName("브랜드 필터 없는 가격순 1페이지(20개, 브랜드 13개)도 브랜드 수와 무관하게 SQL 3개로 끝난다.")
        @Test
        void executesThreeStatements_whenNotFilteredByBrand() {
            // arrange
            sqlCounter.reset();

            // act
            List<ProductInfo> result = productFacade.getProducts(null, "price_asc", 0, 20);

            // assert
            assertThat(result).hasSize(20);
            assertThat(result.stream().map(ProductInfo::brandName).distinct()).hasSize(13);
            assertThat(sqlCounter.count()).isEqualTo(3);
        }
    }
}

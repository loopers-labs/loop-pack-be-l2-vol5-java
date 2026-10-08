package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class BrandFacadeIntegrationTest {

    private final BrandFacade brandFacade;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public BrandFacadeIntegrationTest(
        BrandFacade brandFacade,
        BrandJpaRepository brandJpaRepository,
        ProductJpaRepository productJpaRepository,
        JdbcTemplate jdbcTemplate,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.brandFacade = brandFacade;
        this.brandJpaRepository = brandJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("브랜드를 삭제하는 중 실패하면, ")
    @Nested
    class DeleteBrandFailure {
        private static final String FAILING_PRODUCT_NAME = "삭제 실패 상품";
        private static final String FAIL_CONSTRAINT = "chk_brand_delete_fail";

        @AfterEach
        void dropFailConstraint() {
            jdbcTemplate.execute("ALTER TABLE products DROP CHECK " + FAIL_CONSTRAINT);
        }

        /**
         * flush 순서는 영속성 컨텍스트 진입 순서(브랜드 → 상품 ID 오름차순)이므로
         * ID 가 큰 두 번째 상품의 UPDATE 만 DB 에서 거절되게 하면,
         * 앞선 브랜드·첫 상품 UPDATE 가 이미 나간 뒤 실패하는 상황이 된다.
         */
        @DisplayName("일부 UPDATE 가 DB 에 나간 뒤 실패해도, 브랜드와 상품 변경이 전부 취소된다.")
        @Test
        void rollsBackAllChanges_whenLaterUpdateFails() {
            // arrange
            Long brandId = brandJpaRepository.save(new Brand("루퍼스")).getId();
            Long firstProductId = productJpaRepository.save(new Product(brandId, "정상 상품", new Price(1000L))).getId();
            Long failingProductId = productJpaRepository.save(new Product(brandId, FAILING_PRODUCT_NAME, new Price(1000L))).getId();
            jdbcTemplate.execute(
                "ALTER TABLE products ADD CONSTRAINT " + FAIL_CONSTRAINT
                    + " CHECK (deleted_at IS NULL OR name <> '" + FAILING_PRODUCT_NAME + "')"
            );

            // act
            assertThrows(RuntimeException.class, () -> brandFacade.deleteBrand(brandId));

            // assert - 새 트랜잭션에서 재조회해 DB 상태를 확인한다
            assertThat(brandJpaRepository.findById(brandId).orElseThrow().getDeletedAt()).isNull();
            assertThat(productJpaRepository.findById(firstProductId).orElseThrow().getDeletedAt()).isNull();
            assertThat(productJpaRepository.findById(failingProductId).orElseThrow().getDeletedAt()).isNull();
        }
    }
}
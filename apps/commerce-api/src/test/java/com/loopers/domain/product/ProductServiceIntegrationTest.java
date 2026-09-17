package com.loopers.domain.product;

import com.loopers.domain.common.PageCondition;
import com.loopers.domain.error.DomainErrorType;
import com.loopers.domain.error.DomainException;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class ProductServiceIntegrationTest {

    private static final Long BRAND_ID = 1L;

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Product saveProduct(Long brandId, long stock) {
        return productJpaRepository.save(new Product(brandId, "가방", 30_000L, stock));
    }

    private Product deletedProduct(Long brandId) {
        Product product = new Product(brandId, "가방", 30_000L, 10L);
        product.delete();
        return productJpaRepository.save(product);
    }

    private Product reload(Product product) {
        return productJpaRepository.findById(product.getId()).orElseThrow();
    }

    @DisplayName("상품을 등록할 때, ")
    @Nested
    class Register {

        @DisplayName("유효한 값이면, 저장되고 다시 조회할 수 있다.")
        @Test
        void savesProduct_whenValuesAreValid() {
            // act
            Product product = productService.register(BRAND_ID, "가방", 30_000L, 10L);

            // assert
            Product found = reload(product);
            assertAll(
                () -> assertThat(found.getBrandId()).isEqualTo(BRAND_ID),
                () -> assertThat(found.getName()).isEqualTo("가방"),
                () -> assertThat(found.getPrice()).isEqualTo(30_000L),
                () -> assertThat(found.getStock()).isEqualTo(10L)
            );
        }

        @DisplayName("가격이 범위를 넘으면, INVALID_VALUE 예외가 발생하고 저장되지 않는다. (P-3)")
        @Test
        void throwsInvalidValue_whenPriceIsOutOfRange() {
            // act
            DomainException result = assertThrows(DomainException.class,
                () -> productService.register(BRAND_ID, "가방", 10_000_001L, 10L));

            // assert
            assertAll(
                () -> assertThat(result.getType()).isEqualTo(DomainErrorType.INVALID_VALUE),
                () -> assertThat(productJpaRepository.count()).isZero()
            );
        }
    }

    @DisplayName("상품을 수정할 때, ")
    @Nested
    class Update {

        @DisplayName("이름·가격만 바뀌고 브랜드·재고는 유지된다. (PRD-001)")
        @Test
        void updatesNameAndPriceOnly() {
            // arrange
            Product product = saveProduct(BRAND_ID, 10L);

            // act
            productService.update(product.getId(), "새 가방", 12_000L);

            // assert
            Product found = reload(product);
            assertAll(
                () -> assertThat(found.getName()).isEqualTo("새 가방"),
                () -> assertThat(found.getPrice()).isEqualTo(12_000L),
                () -> assertThat(found.getBrandId()).isEqualTo(BRAND_ID),
                () -> assertThat(found.getStock()).isEqualTo(10L)
            );
        }

        @DisplayName("삭제된 상품이면, NOT_FOUND 예외가 발생한다. (DEL-002)")
        @Test
        void throwsNotFound_whenProductIsDeleted() {
            // arrange
            Product product = deletedProduct(BRAND_ID);

            // act
            DomainException result = assertThrows(DomainException.class, () -> productService.update(product.getId(), "새 가방", 12_000L));

            // assert
            assertAll(
                () -> assertThat(result.getType()).isEqualTo(DomainErrorType.NOT_FOUND),
                () -> assertThat(reload(product).getName()).isEqualTo("가방")
            );
        }
    }

    @DisplayName("재고를 변경할 때, ")
    @Nested
    class ChangeStock {

        @DisplayName("0 이상인 최종 수량이면, 그 값으로 저장된다. (STK-002)")
        @Test
        void setsStock_whenStockIsNotNegative() {
            // arrange
            Product product = saveProduct(BRAND_ID, 10L);

            // act
            productService.changeStock(product.getId(), 0L);

            // assert
            assertThat(reload(product).getStock()).isZero();
        }

        @DisplayName("음수면, INVALID_VALUE 예외가 발생하고 재고가 유지된다. (STK-002)")
        @Test
        void throwsInvalidValue_whenStockIsNegative() {
            // arrange
            Product product = saveProduct(BRAND_ID, 10L);

            // act
            DomainException result = assertThrows(DomainException.class, () -> productService.changeStock(product.getId(), -1L));

            // assert
            assertAll(
                () -> assertThat(result.getType()).isEqualTo(DomainErrorType.INVALID_VALUE),
                () -> assertThat(reload(product).getStock()).isEqualTo(10L)
            );
        }

        @DisplayName("삭제된 상품이면, NOT_FOUND 예외가 발생한다. (DEL-002)")
        @Test
        void throwsNotFound_whenProductIsDeleted() {
            // arrange
            Product product = deletedProduct(BRAND_ID);

            // act
            DomainException result = assertThrows(DomainException.class, () -> productService.changeStock(product.getId(), 5L));

            // assert
            assertThat(result.getType()).isEqualTo(DomainErrorType.NOT_FOUND);
        }
    }

    @DisplayName("상품을 삭제할 때, ")
    @Nested
    class Delete {

        @DisplayName("삭제되지 않은 상품이면, 행은 남고 삭제 시각이 기록된다. (결정 1)")
        @Test
        void softDeletesProduct_whenProductIsActive() {
            // arrange
            Product product = saveProduct(BRAND_ID, 10L);

            // act
            productService.delete(product.getId());

            // assert
            assertThat(reload(product).getDeletedAt()).isNotNull();
        }

        @DisplayName("이미 삭제된 상품이면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenProductIsAlreadyDeleted() {
            // arrange
            Product product = deletedProduct(BRAND_ID);

            // act
            DomainException result = assertThrows(DomainException.class, () -> productService.delete(product.getId()));

            // assert
            assertThat(result.getType()).isEqualTo(DomainErrorType.NOT_FOUND);
        }
    }

    @DisplayName("관리자 상품 목록을 조회할 때, ")
    @Nested
    class GetLatestProducts {

        @DisplayName("삭제된 상품을 제외하고 최신순으로 페이지 조회하며, brandId가 있으면 그 브랜드 상품만 조회한다.")
        @Test
        void returnsActiveProductsInLatestOrder_withOptionalBrandFilter() {
            // arrange
            Product first = saveProduct(BRAND_ID, 10L);
            Product second = saveProduct(BRAND_ID, 10L);
            Product other = saveProduct(2L, 10L);
            deletedProduct(BRAND_ID);

            // act
            List<Product> all = productService.getLatestProducts(null, new PageCondition(0, 2));
            List<Product> brandOnly = productService.getLatestProducts(BRAND_ID, new PageCondition(0, 20));

            // assert
            assertAll(
                () -> assertThat(all).extracting(Product::getId).containsExactly(other.getId(), second.getId()),
                () -> assertThat(productService.countActiveProducts(null)).isEqualTo(3L),
                () -> assertThat(brandOnly).extracting(Product::getId).containsExactly(second.getId(), first.getId()),
                () -> assertThat(productService.countActiveProducts(BRAND_ID)).isEqualTo(2L)
            );
        }
    }

    @DisplayName("브랜드에 삭제되지 않은 상품이 있는지 확인할 때, ")
    @Nested
    class HasActiveProducts {

        @DisplayName("재고 0인 미삭제 상품이 있으면, true를 반환한다. (DEL-001)")
        @Test
        void returnsTrue_whenActiveProductWithZeroStockExists() {
            // arrange
            saveProduct(BRAND_ID, 0L);

            // act & assert
            assertThat(productService.hasActiveProducts(BRAND_ID)).isTrue();
        }

        @DisplayName("삭제된 상품만 있거나 다른 브랜드 상품만 있으면, false를 반환한다. (DEL-001)")
        @Test
        void returnsFalse_whenOnlyDeletedOrOtherBrandProductsExist() {
            // arrange
            deletedProduct(BRAND_ID);
            saveProduct(2L, 10L);

            // act & assert
            assertThat(productService.hasActiveProducts(BRAND_ID)).isFalse();
        }
    }
}

package com.loopers.domain.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandErrorCode;
import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductTest {

    private static final String NAME_OF_50 = "가".repeat(50);
    private static final long MAX_PRICE = 100_000_000L;

    private static Brand brand() {
        return new Brand("브랜드", "설명");
    }

    @DisplayName("상품을 생성할 때, ")
    @Nested
    class Create {
        @DisplayName("이름과 가격이 범위 안이면, 생성된다.")
        @ParameterizedTest
        @ValueSource(longs = {0L, MAX_PRICE})
        void createsProduct_whenNameAndPriceAreWithinLimit(long price) {
            // act
            Product product = new Product(brand(), NAME_OF_50, price);

            // assert
            assertAll(
                () -> assertThat(product.getName()).isEqualTo(NAME_OF_50),
                () -> assertThat(product.getPrice()).isEqualTo(price),
                () -> assertThat(product.isDeleted()).isFalse()
            );
        }

        @DisplayName("삭제된 브랜드면, BRAND_NOT_FOUND 예외가 발생한다. (BRD-02 생성 입구)")
        @Test
        void throwsBrandNotFound_whenBrandIsDeleted() {
            // arrange
            Brand deletedBrand = brand();
            deletedBrand.delete();

            // act
            CoreException result = assertThrows(CoreException.class, () -> new Product(deletedBrand, "상품", 1_000L));

            // assert
            assertThat(result.getErrorCode()).isEqualTo(BrandErrorCode.BRAND_NOT_FOUND);
        }

        @DisplayName("이름이 비었거나 공백뿐이면, INVALID_PRODUCT_NAME 예외가 발생한다.")
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void throwsInvalidProductName_whenNameIsBlank(String name) {
            // act
            CoreException result = assertThrows(CoreException.class, () -> new Product(brand(), name, 1_000L));

            // assert
            assertThat(result.getErrorCode()).isEqualTo(ProductErrorCode.INVALID_PRODUCT_NAME);
        }

        @DisplayName("이름이 50자를 넘으면, INVALID_PRODUCT_NAME 예외가 발생한다.")
        @Test
        void throwsInvalidProductName_whenNameExceedsLimit() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> new Product(brand(), NAME_OF_50 + "가", 1_000L));

            // assert
            assertThat(result.getErrorCode()).isEqualTo(ProductErrorCode.INVALID_PRODUCT_NAME);
        }

        @DisplayName("가격이 0원 미만이거나 1억 원을 넘으면, INVALID_PRICE 예외가 발생한다.")
        @ParameterizedTest
        @ValueSource(longs = {-1L, MAX_PRICE + 1})
        void throwsInvalidPrice_whenPriceIsOutOfRange(long price) {
            // act
            CoreException result = assertThrows(CoreException.class, () -> new Product(brand(), "상품", price));

            // assert
            assertThat(result.getErrorCode()).isEqualTo(ProductErrorCode.INVALID_PRICE);
        }
    }

    @DisplayName("상품을 수정할 때, ")
    @Nested
    class Update {
        @DisplayName("이름과 가격이 범위 안이면, 바뀐다.")
        @Test
        void updatesProduct_whenNameAndPriceAreValid() {
            // arrange
            Product product = new Product(brand(), "상품", 1_000L);

            // act
            product.update("새 상품", 2_000L);

            // assert
            assertAll(
                () -> assertThat(product.getName()).isEqualTo("새 상품"),
                () -> assertThat(product.getPrice()).isEqualTo(2_000L)
            );
        }

        @DisplayName("가격이 범위를 벗어나면, INVALID_PRICE 예외가 발생하고 기존 값이 유지된다.")
        @Test
        void throwsInvalidPrice_andKeepsValues_whenPriceIsOutOfRange() {
            // arrange
            Product product = new Product(brand(), "상품", 1_000L);

            // act
            CoreException result = assertThrows(CoreException.class, () -> product.update("새 상품", -1L));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ProductErrorCode.INVALID_PRICE),
                () -> assertThat(product.getName()).isEqualTo("상품"),
                () -> assertThat(product.getPrice()).isEqualTo(1_000L)
            );
        }

        @DisplayName("삭제된 상품이면, PRODUCT_NOT_FOUND 예외가 발생하고 기존 값이 유지된다. (PRD-02)")
        @Test
        void throwsProductNotFound_andKeepsValues_whenProductIsDeleted() {
            // arrange
            Product product = new Product(brand(), "상품", 1_000L);
            product.delete();

            // act
            CoreException result = assertThrows(CoreException.class, () -> product.update("새 상품", 2_000L));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND),
                () -> assertThat(product.getName()).isEqualTo("상품"),
                () -> assertThat(product.getPrice()).isEqualTo(1_000L)
            );
        }
    }

    @DisplayName("삭제된 상품의 재고를 바꾸면, PRODUCT_NOT_FOUND 예외가 발생하고 기존 재고가 유지된다. (PRD-04)")
    @Test
    void throwsProductNotFound_andKeepsStock_whenChangingStockOfDeletedProduct() {
        // arrange
        Product product = new Product(brand(), "상품", 1_000L);
        product.changeStock(5);
        product.delete();

        // act
        CoreException result = assertThrows(CoreException.class, () -> product.changeStock(10));

        // assert
        assertAll(
            () -> assertThat(result.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND),
            () -> assertThat(product.getStock()).isEqualTo(5)
        );
    }
}

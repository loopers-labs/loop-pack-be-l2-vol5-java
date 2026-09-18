package com.loopers.domain.product;

import com.loopers.domain.brand.Brand;
import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductStockTest {

    private static Product productWithStock(int stock) {
        Product product = new Product(new Brand("브랜드", "설명"), "상품", 1_000L);
        product.changeStock(stock);
        return product;
    }

    @DisplayName("재고를 차감할 때, ")
    @Nested
    class Decrease {
        @DisplayName("보유량보다 많이 차감하면, OUT_OF_STOCK 예외가 발생하고 재고는 그대로다.")
        @Test
        void throwsOutOfStock_andKeepsStock_whenQuantityExceedsStock() {
            // arrange
            Product product = productWithStock(5);

            // act
            CoreException result = assertThrows(CoreException.class, () -> product.decrease(6));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ProductErrorCode.OUT_OF_STOCK),
                () -> assertThat(product.getStock()).isEqualTo(5)
            );
        }

        @DisplayName("0 이하의 수량을 차감하면, OUT_OF_STOCK 예외가 발생하고 재고는 그대로다.")
        @ParameterizedTest
        @ValueSource(ints = {0, -1})
        void throwsOutOfStock_andKeepsStock_whenQuantityIsNotPositive(int quantity) {
            // arrange
            Product product = productWithStock(5);

            // act
            CoreException result = assertThrows(CoreException.class, () -> product.decrease(quantity));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ProductErrorCode.OUT_OF_STOCK),
                () -> assertThat(product.getStock()).isEqualTo(5)
            );
        }

        @DisplayName("보유량 이하를 차감하면, 그만큼 재고가 줄어든다.")
        @Test
        void decreasesStock_whenQuantityIsWithinStock() {
            // arrange
            Product product = productWithStock(5);

            // act
            product.decrease(2);

            // assert
            assertThat(product.getStock()).isEqualTo(3);
        }

        @DisplayName("보유량 전부를 차감하면, 재고가 0 이 된다.")
        @Test
        void decreasesStockToZero_whenQuantityEqualsStock() {
            // arrange
            Product product = productWithStock(5);

            // act
            product.decrease(5);

            // assert
            assertThat(product.getStock()).isZero();
        }
    }

    @DisplayName("재고를 차감할 수 있는지 판단할 때, ")
    @Nested
    class CanDecrease {
        @DisplayName("보유량 이하의 양수면, 참이다.")
        @ParameterizedTest
        @ValueSource(ints = {1, 5})
        void returnsTrue_whenQuantityIsPositiveAndWithinStock(int quantity) {
            // arrange
            Product product = productWithStock(5);

            // act & assert
            assertThat(product.canDecrease(quantity)).isTrue();
        }

        @DisplayName("보유량보다 많거나 0 이하면, 거짓이다.")
        @ParameterizedTest
        @ValueSource(ints = {6, 0, -1})
        void returnsFalse_whenQuantityExceedsStockOrIsNotPositive(int quantity) {
            // arrange
            Product product = productWithStock(5);

            // act & assert
            assertThat(product.canDecrease(quantity)).isFalse();
        }
    }

    @DisplayName("최종 재고를 설정할 때, ")
    @Nested
    class ChangeStock {
        @DisplayName("음수면, INVALID_STOCK 예외가 발생하고 기존 재고는 그대로다.")
        @Test
        void throwsInvalidStock_andKeepsStock_whenStockIsNegative() {
            // arrange
            Product product = productWithStock(5);

            // act
            CoreException result = assertThrows(CoreException.class, () -> product.changeStock(-1));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ProductErrorCode.INVALID_STOCK),
                () -> assertThat(product.getStock()).isEqualTo(5)
            );
        }

        @DisplayName("0 이상이면, 그 값으로 재고가 바뀐다.")
        @ParameterizedTest
        @ValueSource(ints = {0, 10})
        void changesStock_whenStockIsNotNegative(int stock) {
            // arrange
            Product product = productWithStock(5);

            // act
            product.changeStock(stock);

            // assert
            assertThat(product.getStock()).isEqualTo(stock);
        }
    }

    @DisplayName("상품을 생성하면, 재고는 0 이다.")
    @Test
    void startsWithZeroStock() {
        // act
        Product product = new Product(new Brand("브랜드", "설명"), "상품", 1_000L);

        // assert
        assertThat(product.getStock()).isZero();
    }
}

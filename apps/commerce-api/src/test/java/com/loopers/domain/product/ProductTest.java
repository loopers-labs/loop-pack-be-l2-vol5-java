package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductTest {

    @DisplayName("Product를 만들 때,")
    @Nested
    class Create {
        @DisplayName("이름과 가격이 유효하면 재고 0인 Product를 생성한다.")
        @Test
        void createsProductWithZeroStock_whenNameAndPriceAreValid() {
            // arrange
            // act
            Product product = Product.create(42L, "Air Max", 100_000L);

            // assert
            assertAll(
                () -> assertThat(product.getBrandId()).isEqualTo(42L),
                () -> assertThat(product.getName()).isEqualTo("Air Max"),
                () -> assertThat(product.getPrice()).isEqualTo(100_000L),
                () -> assertThat(product.getStock().amount()).isZero()
            );
        }

        @DisplayName("이름이 공백만으로 구성되면 BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsException_whenNameIsBlank() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                Product.create(42L, " ", 100_000L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("이름이 100자를 넘으면 BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsException_whenNameExceedsMaximumLength() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                Product.create(42L, "a".repeat(101), 100_000L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("가격이 1원 미만이면 BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsException_whenPriceIsLessThanMinimum() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                Product.create(42L, "Air Max", 0L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("가격이 100,000,000원을 넘으면 BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsException_whenPriceExceedsMaximum() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                Product.create(42L, "Air Max", 100_000_001L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("Product 재고를 변경할 때,")
    @Nested
    class ChangeStock {
        @DisplayName("0 이상인 최종 수량이면 재고를 변경한다.")
        @Test
        void changesStock_whenFinalQuantityIsZeroOrMore() {
            // arrange
            Product product = Product.create(42L, "Air Max", 100_000L);

            // act
            product.changeStockTo(5L);

            // assert
            assertThat(product.getStock().amount()).isEqualTo(5L);
        }

        @DisplayName("음수인 최종 수량이면 BAD_REQUEST 예외가 발생하고 기존 재고를 유지한다.")
        @Test
        void keepsStock_whenFinalQuantityIsNegative() {
            // arrange
            Product product = Product.create(42L, "Air Max", 100_000L);
            product.changeStockTo(5L);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                product.changeStockTo(-1L);
            });

            // assert
            assertAll(
                () -> assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                () -> assertThat(product.getStock().amount()).isEqualTo(5L)
            );
        }
    }

    @DisplayName("Product 정보를 수정할 때,")
    @Nested
    class Update {
        @DisplayName("유효한 이름과 가격이면 상품 정보를 변경한다.")
        @Test
        void changesDetails_whenNameAndPriceAreValid() {
            // arrange
            Product product = Product.create(42L, "Air Max", 100_000L);

            // act
            product.updateDetails("Air Force", 120_000L);

            // assert
            assertAll(
                () -> assertThat(product.getName()).isEqualTo("Air Force"),
                () -> assertThat(product.getPrice()).isEqualTo(120_000L)
            );
        }

        @DisplayName("이름 또는 가격이 유효하지 않으면 BAD_REQUEST 예외가 발생하고 기존 정보를 유지한다.")
        @Test
        void keepsDetails_whenNameOrPriceIsInvalid() {
            // arrange
            Product product = Product.create(42L, "Air Max", 100_000L);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                product.updateDetails(" ", 120_000L);
            });

            // assert
            assertAll(
                () -> assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                () -> assertThat(product.getName()).isEqualTo("Air Max"),
                () -> assertThat(product.getPrice()).isEqualTo(100_000L)
            );
        }
    }
}

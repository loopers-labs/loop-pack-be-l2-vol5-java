package com.loopers.domain.product;

import com.loopers.domain.brand.Brand;
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
            Brand brand = Brand.create("Nike");

            // act
            Product product = Product.create(brand, "Air Max", 100_000L);

            // assert
            assertAll(
                () -> assertThat(product.getBrand()).isEqualTo(brand),
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
                Product.create(Brand.create("Nike"), " ", 100_000L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("이름이 100자를 넘으면 BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsException_whenNameExceedsMaximumLength() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                Product.create(Brand.create("Nike"), "a".repeat(101), 100_000L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("가격이 1원 미만이면 BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsException_whenPriceIsLessThanMinimum() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                Product.create(Brand.create("Nike"), "Air Max", 0L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("가격이 100,000,000원을 넘으면 BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsException_whenPriceExceedsMaximum() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                Product.create(Brand.create("Nike"), "Air Max", 100_000_001L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }
}

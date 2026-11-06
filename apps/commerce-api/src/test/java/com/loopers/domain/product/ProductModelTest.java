package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductModelTest {

    @DisplayName("상품을 생성할 때, ")
    @Nested
    class Create {
        @DisplayName("유효한 값이 주어지면, 정상적으로 생성된다.")
        @Test
        void createsProduct_whenValuesAreValid() {
            // act
            ProductModel product = new ProductModel(1L, "runner", 10_000L, 5);

            // assert
            assertThat(product.getBrandId()).isEqualTo(1L);
            assertThat(product.getName()).isEqualTo("runner");
            assertThat(product.getPrice()).isEqualTo(10_000L);
            assertThat(product.getStock()).isEqualTo(5);
        }

        @DisplayName("이름이 공백이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenNameIsBlank() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> new ProductModel(1L, "  ", 10_000L, 5));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("가격이 0 이하이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenPriceIsNotPositive() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> new ProductModel(1L, "runner", 0L, 5));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("재고를 변경할 때, ")
    @Nested
    class ChangeStock {
        @DisplayName("0 이상의 최종 수량을 주면, 재고가 그 값으로 바뀐다.")
        @Test
        void setsStock_whenQuantityIsNonNegative() {
            // arrange
            ProductModel product = new ProductModel(1L, "runner", 10_000L, 5);

            // act
            product.changeStock(20);

            // assert
            assertThat(product.getStock()).isEqualTo(20);
        }
    }

    @DisplayName("재고를 차감할 때, ")
    @Nested
    class DecreaseStock {
        @DisplayName("보유 수량보다 많은 수량을 요청하면, BAD_REQUEST 예외가 발생하고 재고가 유지된다.")
        @Test
        void throwsBadRequestException_whenQuantityExceedsStock() {
            // arrange
            ProductModel product = new ProductModel(1L, "runner", 10_000L, 5);

            // act
            CoreException result = assertThrows(CoreException.class, () -> product.decreaseStock(6));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            assertThat(product.getStock()).isEqualTo(5);
        }
    }
}

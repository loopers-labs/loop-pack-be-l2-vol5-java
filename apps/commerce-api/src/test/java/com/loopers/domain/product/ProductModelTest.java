package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductModelTest {
    @DisplayName("상품 모델을 생성할 때, ")
    @Nested
    class Create {
        @DisplayName("이름, 가격, 브랜드ID, 초기재고가 모두 주어지면, 정상적으로 생성된다.")
        @Test
        void createsProductModel_whenAllFieldsAreProvided() {
            // act
            ProductModel product = new ProductModel("에어맥스", 129_000L, 1L, 10);

            // assert
            assertAll(
                () -> assertThat(product.getName()).isEqualTo("에어맥스"),
                () -> assertThat(product.getPrice()).isEqualTo(129_000L),
                () -> assertThat(product.getBrandId()).isEqualTo(1L),
                () -> assertThat(product.getRemainingStock()).isEqualTo(10)
            );
        }

        @DisplayName("이름이 빈 값이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenNameIsBlank() {
            // act
            CoreException result = assertThrows(CoreException.class, () ->
                new ProductModel("   ", 1000L, 1L, 10)
            );

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("이름이 255자를 초과하면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenNameExceeds255Characters() {
            // arrange
            String name = "가".repeat(256);

            // act
            CoreException result = assertThrows(CoreException.class, () ->
                new ProductModel(name, 1000L, 1L, 10)
            );

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("가격이 0이면, 정상적으로 생성된다.")
        @Test
        void createsProductModel_whenPriceIsZero() {
            // act
            ProductModel product = new ProductModel("무료 체험판", 0L, 1L, 10);

            // assert
            assertThat(product.getPrice()).isEqualTo(0L);
        }

        @DisplayName("가격이 음수이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenPriceIsNegative() {
            // act
            CoreException result = assertThrows(CoreException.class, () ->
                new ProductModel("에어맥스", -1L, 1L, 10)
            );

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("가격이 없으면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenPriceIsNull() {
            // act
            CoreException result = assertThrows(CoreException.class, () ->
                new ProductModel("에어맥스", null, 1L, 10)
            );

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("브랜드ID가 없으면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenBrandIdIsNull() {
            // act
            CoreException result = assertThrows(CoreException.class, () ->
                new ProductModel("에어맥스", 1000L, null, 10)
            );

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("초기재고가 음수이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenInitialStockIsNegative() {
            // act
            CoreException result = assertThrows(CoreException.class, () ->
                new ProductModel("에어맥스", 1000L, 1L, -1)
            );

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("상품 모델을 수정할 때, ")
    @Nested
    class Update {
        @DisplayName("이름과 가격이 주어지면, 정상적으로 수정되고 브랜드ID는 유지된다.")
        @Test
        void updatesNameAndPrice_andKeepsBrandId() {
            // arrange
            ProductModel product = new ProductModel("에어맥스", 129_000L, 1L, 10);

            // act
            product.update("에어맥스 90", 139_000L);

            // assert
            assertAll(
                () -> assertThat(product.getName()).isEqualTo("에어맥스 90"),
                () -> assertThat(product.getPrice()).isEqualTo(139_000L),
                () -> assertThat(product.getBrandId()).isEqualTo(1L)
            );
        }

        @DisplayName("가격이 음수이면, BAD_REQUEST 예외가 발생하고 기존 값이 유지된다.")
        @Test
        void throwsBadRequestException_whenPriceIsNegative() {
            // arrange
            ProductModel product = new ProductModel("에어맥스", 129_000L, 1L, 10);

            // act
            CoreException result = assertThrows(CoreException.class, () ->
                product.update("에어맥스 90", -1L)
            );

            // assert
            assertAll(
                () -> assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                () -> assertThat(product.getName()).isEqualTo("에어맥스"),
                () -> assertThat(product.getPrice()).isEqualTo(129_000L)
            );
        }
    }

    @DisplayName("상품 재고를 변경할 때, ")
    @Nested
    class ChangeStock {
        @DisplayName("0 이상의 수량을 주면, 그 값으로 절대 설정된다.")
        @Test
        void setsStock_whenQuantityIsZeroOrPositive() {
            // arrange
            ProductModel product = new ProductModel("에어맥스", 129_000L, 1L, 10);

            // act
            product.changeStock(100);

            // assert
            assertThat(product.getRemainingStock()).isEqualTo(100);
        }

        @DisplayName("음수 수량을 주면, BAD_REQUEST 예외가 발생하고 재고는 그대로 유지된다.")
        @Test
        void throwsBadRequestException_whenQuantityIsNegative() {
            // arrange
            ProductModel product = new ProductModel("에어맥스", 129_000L, 1L, 10);

            // act
            CoreException result = assertThrows(CoreException.class, () -> product.changeStock(-1));

            // assert
            assertAll(
                () -> assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                () -> assertThat(product.getRemainingStock()).isEqualTo(10)
            );
        }
    }

    @DisplayName("주문 확정으로 재고를 차감할 때, ")
    @Nested
    class DecreaseStock {
        @DisplayName("재고 이하의 양수 수량이면, 그만큼 상대적으로 차감된다.")
        @Test
        void decreasesStock_whenQuantityIsWithinRemaining() {
            // arrange
            ProductModel product = new ProductModel("에어맥스", 129_000L, 1L, 10);

            // act
            product.decreaseStock(3);

            // assert
            assertThat(product.getRemainingStock()).isEqualTo(7);
        }

        @DisplayName("재고보다 많은 수량이면, BAD_REQUEST 예외가 발생하고 재고는 그대로 유지된다.")
        @Test
        void throwsBadRequestException_whenQuantityExceedsRemaining() {
            // arrange
            ProductModel product = new ProductModel("에어맥스", 129_000L, 1L, 10);

            // act
            CoreException result = assertThrows(CoreException.class, () -> product.decreaseStock(11));

            // assert
            assertAll(
                () -> assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                () -> assertThat(product.getRemainingStock()).isEqualTo(10)
            );
        }

        @DisplayName("0 이하의 수량이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenQuantityIsZeroOrNegative() {
            // arrange
            ProductModel product = new ProductModel("에어맥스", 129_000L, 1L, 10);

            // act
            CoreException result = assertThrows(CoreException.class, () -> product.decreaseStock(0));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }
}

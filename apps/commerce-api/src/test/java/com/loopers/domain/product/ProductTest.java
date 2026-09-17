package com.loopers.domain.product;

import com.loopers.domain.error.DomainErrorType;
import com.loopers.domain.error.DomainException;
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

    private static final Long BRAND_ID = 1L;
    private static final String NAME_100 = "가".repeat(100);
    private static final String NAME_101 = "가".repeat(101);
    private static final long MAX_PRICE = 10_000_000L;

    private Product product(long stock) {
        return new Product(BRAND_ID, "가방", 30_000L, stock);
    }

    @DisplayName("상품을 생성할 때, ")
    @Nested
    class Create {

        @DisplayName("브랜드 ID·이름·가격·재고가 유효하면, 정상적으로 생성된다.")
        @Test
        void createsProduct_whenAllValuesAreValid() {
            // act
            Product product = new Product(BRAND_ID, "가방", 30_000L, 10L);

            // assert
            assertAll(
                () -> assertThat(product.getBrandId()).isEqualTo(BRAND_ID),
                () -> assertThat(product.getName()).isEqualTo("가방"),
                () -> assertThat(product.getPrice()).isEqualTo(30_000L),
                () -> assertThat(product.getStock()).isEqualTo(10L)
            );
        }

        @DisplayName("경계값인 이름 100자, 가격 0원과 1,000만 원, 재고 0이면 생성된다. (P-3, T-8)")
        @Test
        void createsProduct_whenValuesAreOnBoundary() {
            // act
            Product minimum = new Product(BRAND_ID, NAME_100, 0L, 0L);
            Product maximum = new Product(BRAND_ID, NAME_100, MAX_PRICE, 0L);

            // assert
            assertAll(
                () -> assertThat(minimum.getName()).hasSize(100),
                () -> assertThat(minimum.getPrice()).isEqualTo(0L),
                () -> assertThat(minimum.getStock()).isEqualTo(0L),
                () -> assertThat(maximum.getPrice()).isEqualTo(MAX_PRICE)
            );
        }

        @DisplayName("브랜드 ID가 없으면, INVALID_VALUE 예외가 발생한다. (PRD-001)")
        @Test
        void throwsInvalidValue_whenBrandIdIsNull() {
            // act
            DomainException result = assertThrows(DomainException.class, () -> new Product(null, "가방", 30_000L, 10L));

            // assert
            assertThat(result.getType()).isEqualTo(DomainErrorType.INVALID_VALUE);
        }

        @DisplayName("이름이 없거나 빈칸이면, INVALID_VALUE 예외가 발생한다. (T-8)")
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void throwsInvalidValue_whenNameIsBlank(String name) {
            // act
            DomainException result = assertThrows(DomainException.class, () -> new Product(BRAND_ID, name, 30_000L, 10L));

            // assert
            assertThat(result.getType()).isEqualTo(DomainErrorType.INVALID_VALUE);
        }

        @DisplayName("이름이 100자를 넘으면, INVALID_VALUE 예외가 발생한다. (P-3)")
        @Test
        void throwsInvalidValue_whenNameExceeds100() {
            // act
            DomainException result = assertThrows(DomainException.class, () -> new Product(BRAND_ID, NAME_101, 30_000L, 10L));

            // assert
            assertThat(result.getType()).isEqualTo(DomainErrorType.INVALID_VALUE);
        }

        @DisplayName("가격이 없거나 0원 미만 또는 1,000만 원 초과이면, INVALID_VALUE 예외가 발생한다. (P-3, T-8)")
        @ParameterizedTest
        @ValueSource(longs = {-1L, 10_000_001L})
        void throwsInvalidValue_whenPriceIsOutOfRange(long price) {
            // act
            DomainException outOfRange = assertThrows(DomainException.class, () -> new Product(BRAND_ID, "가방", price, 10L));
            DomainException nullPrice = assertThrows(DomainException.class, () -> new Product(BRAND_ID, "가방", null, 10L));

            // assert
            assertAll(
                () -> assertThat(outOfRange.getType()).isEqualTo(DomainErrorType.INVALID_VALUE),
                () -> assertThat(nullPrice.getType()).isEqualTo(DomainErrorType.INVALID_VALUE)
            );
        }

        @DisplayName("재고가 없거나 음수이면, INVALID_VALUE 예외가 발생한다. (STK-002)")
        @Test
        void throwsInvalidValue_whenStockIsNullOrNegative() {
            // act
            DomainException negative = assertThrows(DomainException.class, () -> new Product(BRAND_ID, "가방", 30_000L, -1L));
            DomainException nullStock = assertThrows(DomainException.class, () -> new Product(BRAND_ID, "가방", 30_000L, null));

            // assert
            assertAll(
                () -> assertThat(negative.getType()).isEqualTo(DomainErrorType.INVALID_VALUE),
                () -> assertThat(nullStock.getType()).isEqualTo(DomainErrorType.INVALID_VALUE)
            );
        }
    }

    @DisplayName("상품 정보를 수정할 때, ")
    @Nested
    class Update {

        @DisplayName("유효한 이름과 가격이면, 이름과 가격만 바뀌고 브랜드와 재고는 유지된다. (PRD-001)")
        @Test
        void updatesNameAndPriceOnly_whenValuesAreValid() {
            // arrange
            Product product = product(10L);

            // act
            product.update("새 가방", 35_000L);

            // assert
            assertAll(
                () -> assertThat(product.getName()).isEqualTo("새 가방"),
                () -> assertThat(product.getPrice()).isEqualTo(35_000L),
                () -> assertThat(product.getBrandId()).isEqualTo(BRAND_ID),
                () -> assertThat(product.getStock()).isEqualTo(10L)
            );
        }

        @DisplayName("이름이나 가격이 유효하지 않으면, INVALID_VALUE 예외가 발생하고 기존 값이 유지된다. (P-3)")
        @Test
        void throwsInvalidValueAndKeepsValues_whenValuesAreInvalid() {
            // arrange
            Product product = product(10L);

            // act
            DomainException blankName = assertThrows(DomainException.class, () -> product.update(" ", 35_000L));
            DomainException tooExpensive = assertThrows(DomainException.class, () -> product.update("새 가방", 10_000_001L));

            // assert
            assertAll(
                () -> assertThat(blankName.getType()).isEqualTo(DomainErrorType.INVALID_VALUE),
                () -> assertThat(tooExpensive.getType()).isEqualTo(DomainErrorType.INVALID_VALUE),
                () -> assertThat(product.getName()).isEqualTo("가방"),
                () -> assertThat(product.getPrice()).isEqualTo(30_000L)
            );
        }
    }

    @DisplayName("재고를 차감할 때, (STK-001)")
    @Nested
    class DecreaseStock {

        @DisplayName("보유 재고 이하의 양수를 차감하면, 그만큼 재고가 줄어든다.")
        @Test
        void decreasesStock_whenQuantityIsWithinStock() {
            // arrange
            Product product = product(5L);

            // act
            product.decreaseStock(2L);

            // assert
            assertThat(product.getStock()).isEqualTo(3L);
        }

        @DisplayName("보유 재고 전부를 차감하면, 재고가 0이 된다.")
        @Test
        void decreasesStockToZero_whenQuantityEqualsStock() {
            // arrange
            Product product = product(5L);

            // act
            product.decreaseStock(5L);

            // assert
            assertThat(product.getStock()).isEqualTo(0L);
        }

        @DisplayName("보유 재고보다 많이 차감하면, CONFLICT 예외가 발생하고 재고가 유지된다.")
        @Test
        void throwsConflictAndKeepsStock_whenQuantityExceedsStock() {
            // arrange
            Product product = product(5L);

            // act
            DomainException result = assertThrows(DomainException.class, () -> product.decreaseStock(6L));

            // assert
            assertAll(
                () -> assertThat(result.getType()).isEqualTo(DomainErrorType.CONFLICT),
                () -> assertThat(product.getStock()).isEqualTo(5L)
            );
        }

        @DisplayName("0 이하의 수량을 차감하면, INVALID_VALUE 예외가 발생하고 재고가 유지된다.")
        @ParameterizedTest
        @ValueSource(longs = {0L, -1L})
        void throwsInvalidValueAndKeepsStock_whenQuantityIsNotPositive(long quantity) {
            // arrange
            Product product = product(5L);

            // act
            DomainException result = assertThrows(DomainException.class, () -> product.decreaseStock(quantity));

            // assert
            assertAll(
                () -> assertThat(result.getType()).isEqualTo(DomainErrorType.INVALID_VALUE),
                () -> assertThat(product.getStock()).isEqualTo(5L)
            );
        }
    }

    @DisplayName("재고를 최종 수량으로 설정할 때, (STK-002)")
    @Nested
    class ChangeStock {

        @DisplayName("0 이상의 수량이면, 그 수량으로 설정된다.")
        @ParameterizedTest
        @ValueSource(longs = {0L, 20L})
        void changesStock_whenStockIsNotNegative(long stock) {
            // arrange
            Product product = product(5L);

            // act
            product.changeStock(stock);

            // assert
            assertThat(product.getStock()).isEqualTo(stock);
        }

        @DisplayName("음수이면, INVALID_VALUE 예외가 발생하고 재고가 유지된다.")
        @Test
        void throwsInvalidValueAndKeepsStock_whenStockIsNegative() {
            // arrange
            Product product = product(5L);

            // act
            DomainException result = assertThrows(DomainException.class, () -> product.changeStock(-1L));

            // assert
            assertAll(
                () -> assertThat(result.getType()).isEqualTo(DomainErrorType.INVALID_VALUE),
                () -> assertThat(product.getStock()).isEqualTo(5L)
            );
        }
    }
}

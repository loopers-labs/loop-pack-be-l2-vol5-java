package com.loopers.product.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductTest {

    @DisplayName("[INV-06] 상품 이름은 앞뒤 공백을 뺀 1자 이상 100자 이하다.")
    @Nested
    class ValidName {

        @DisplayName("[경계값 분석] 이름 길이 1과 100은 허용한다.")
        @Test
        void createsProduct_whenNameLengthIsOnBoundary() {
            // act, assert
            assertAll(
                () -> assertDoesNotThrow(() -> new Product(1L, "a", 1_000L)),
                () -> assertDoesNotThrow(() -> new Product(1L, "a".repeat(100), 1_000L))
            );
        }

        @DisplayName("[경계값 분석] 이름이 비었거나 101자이면 상품 이름 오류로 거절한다.")
        @ParameterizedTest
        @ValueSource(strings = {"", "   ",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
        void throwsInvalidProductName_whenNameIsOutsideRange(String name) {
            // act
            CoreException result = assertThrows(
                CoreException.class,
                () -> new Product(1L, name, 1_000L)
            );

            // assert
            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INVALID_PRODUCT_NAME);
        }

        @DisplayName("[동등 클래스 분할] 앞뒤 공백을 제거하고 입력한 대소문자는 유지한다.")
        @Test
        void trimsName_andKeepsCase() {
            // act
            Product product = new Product(1L, "  Nike  ", 1_000L);

            // assert
            assertThat(product.getName()).isEqualTo("Nike");
        }
    }

    @DisplayName("[INV-08] 상품 가격은 1원 이상 1,000,000,000원 이하다.")
    @Nested
    class ValidPrice {

        @DisplayName("[경계값 분석] 가격 1원과 10억원은 허용한다.")
        @ParameterizedTest
        @ValueSource(longs = {1L, 1_000_000_000L})
        void createsProduct_whenPriceIsOnBoundary(long price) {
            // act
            Product result = new Product(1L, "상품", price);

            // assert
            assertThat(result.getPrice()).isEqualTo(price);
        }

        @DisplayName("[경계값 분석] 가격이 0원 또는 10억원 초과이면 상품 가격 오류로 거절한다.")
        @ParameterizedTest
        @ValueSource(longs = {0L, 1_000_000_001L})
        void throwsInvalidProductPrice_whenPriceIsOutsideRange(long price) {
            // act
            CoreException result = assertThrows(
                CoreException.class,
                () -> new Product(1L, "상품", price)
            );

            // assert
            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INVALID_PRODUCT_PRICE);
        }
    }

    @DisplayName("[INV-09] 상품은 존재하며 삭제되지 않은 브랜드에 속한다.")
    @Nested
    class BelongsToBrand {

        @DisplayName("[동등 클래스 분할] 상품은 자신이 속한 브랜드 식별자를 가진다.")
        @Test
        void keepsBrandId() {
            // act
            Product product = new Product(1L, "상품", 1_000L);

            // assert
            assertThat(product.getBrandId()).isEqualTo(1L);
        }
    }

    @DisplayName("[INV-10] 상품의 소속 브랜드는 만든 뒤에 바뀌지 않는다.")
    @Nested
    class ImmutableBrand {

        @DisplayName("[상태 전이] 같은 브랜드로 수정하면 이름과 가격만 바뀐다.")
        @Test
        void updatesNameAndPrice_andKeepsBrand() {
            // arrange
            Product product = new Product(1L, "수정 전", 1_000L);

            // act
            product.update("수정 후", 2_000L, 1L);

            // assert
            assertAll(
                () -> assertThat(product.getName()).isEqualTo("수정 후"),
                () -> assertThat(product.getPrice()).isEqualTo(2_000L),
                () -> assertThat(product.getBrandId()).isEqualTo(1L)
            );
        }

        @DisplayName("[상태 전이] 다른 브랜드로 수정하면 거절하고, 이름·가격·브랜드는 그대로다.")
        @Test
        void throwsBrandChangeNotAllowed_andKeepsProduct() {
            // arrange
            Product product = new Product(1L, "수정 전", 1_000L);

            // act
            CoreException result = assertThrows(
                CoreException.class,
                () -> product.update("수정 후", 2_000L, 2L)
            );

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode())
                    .isEqualTo(ErrorCode.BRAND_CHANGE_NOT_ALLOWED),
                () -> assertThat(product.getName()).isEqualTo("수정 전"),
                () -> assertThat(product.getPrice()).isEqualTo(1_000L),
                () -> assertThat(product.getBrandId()).isEqualTo(1L)
            );
        }

        @DisplayName("[상태 전이] 삭제해도 브랜드 식별자는 그대로다.")
        @Test
        void keepsBrandId_whenDeleted() {
            // arrange
            Product product = new Product(1L, "상품", 1_000L);

            // act
            product.delete();

            // assert
            assertAll(
                () -> assertThat(product.isDeleted()).isTrue(),
                () -> assertThat(product.getBrandId()).isEqualTo(1L)
            );
        }
    }

    @DisplayName("[INV-11] 삭제된 상품의 이름·가격·재고는 바뀌지 않는다.")
    @Nested
    class ImmutableAfterDeletion {

        @DisplayName("[상태 전이] 삭제된 상품의 수정과 재고 설정을 없는 대상으로 거절하고, 값은 그대로다.")
        @Test
        void rejectsUpdateAndStockChange_whenDeleted() {
            // arrange
            Product product = new Product(1L, "상품", 1_000L);
            product.delete();

            // act
            CoreException updateResult = assertThrows(
                CoreException.class,
                () -> product.update("수정 상품", 2_000L, 1L)
            );
            CoreException stockResult = assertThrows(
                CoreException.class,
                () -> product.changeStock(10)
            );

            // assert
            assertAll(
                () -> assertThat(updateResult.getErrorCode())
                    .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND),
                () -> assertThat(stockResult.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND),
                () -> assertThat(product.getName()).isEqualTo("상품"),
                () -> assertThat(product.getPrice()).isEqualTo(1_000L),
                () -> assertStockQuantity(product, 0),
                () -> assertThat(product.isDeleted()).isTrue()
            );
        }

        @DisplayName("[상태 전이] 삭제된 상품의 재고 차감을 없는 대상으로 거절하고, 재고는 그대로다.")
        @Test
        void throwsProductNotFound_andKeepsStock() {
            // arrange
            Product product = new Product(1L, "상품", 1_000L);
            product.changeStock(5);
            product.delete();

            // act
            CoreException result = assertThrows(
                CoreException.class, () -> product.decreaseStock(1));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND),
                () -> assertStockQuantity(product, 5)
            );
        }
    }

    @DisplayName("[INV-12] 이미 삭제된 상품은 다시 삭제되지 않는다.")
    @Nested
    class RejectRepeatedDeletion {

        @DisplayName("[상태 전이] 재삭제를 없는 대상으로 거절하고, 최초 삭제 시점은 그대로다.")
        @Test
        void throwsProductNotFound_whenAlreadyDeleted() {
            // arrange
            Product product = new Product(1L, "상품", 1_000L);
            product.delete();
            var deletedAt = product.getDeletedAt();

            // act
            CoreException result = assertThrows(CoreException.class, product::delete);

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND),
                () -> assertThat(product.getDeletedAt()).isEqualTo(deletedAt)
            );
        }
    }

    @DisplayName("[INV-13] 새로 만든 상품의 재고는 0이다.")
    @Nested
    class InitialStock {

        @DisplayName("[상태 전이] 생성 직후와 일반 수정 뒤 모두 재고는 0이다.")
        @Test
        void startsWithZeroStock_andUpdateKeepsStock() {
            // arrange
            Product product = new Product(1L, "상품", 1_000L);

            // act
            product.update("수정 상품", 2_000L, 1L);

            // assert
            assertStockQuantity(product, 0);
        }
    }

    @DisplayName("[INV-14] 재고 수량은 0 이상이다.")
    @Nested
    class NonNegativeStock {

        @DisplayName("[경계값 분석] 재고를 0과 1로 설정할 수 있다.")
        @ParameterizedTest
        @ValueSource(ints = {0, 1})
        void changesStock_whenQuantityIsNonNegative(int quantity) {
            // arrange
            Product product = new Product(1L, "상품", 1_000L);

            // act
            product.changeStock(quantity);

            // assert
            assertStockQuantity(product, quantity);
        }

        @DisplayName("[경계값 분석] 재고를 -1로 설정하면 재고 수량 오류로 거절하고, 재고는 그대로다.")
        @Test
        void throwsInvalidStockQuantity_andKeepsStock() {
            // arrange
            Product product = new Product(1L, "상품", 1_000L);

            // act
            CoreException result = assertThrows(
                CoreException.class, () -> product.changeStock(-1));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INVALID_STOCK_QUANTITY),
                () -> assertStockQuantity(product, 0)
            );
        }
    }

    @DisplayName("[INV-15] 차감 수량은 현재 재고 이하다.")
    @Nested
    class DecreaseWithinStock {

        @DisplayName("[경계값 분석] 재고 5에서 5를 차감하면 0이 된다.")
        @Test
        void decreasesStock_whenQuantityEqualsStock() {
            // arrange
            Product product = new Product(1L, "상품", 1_000L);
            product.changeStock(5);

            // act
            product.decreaseStock(5);

            // assert
            assertStockQuantity(product, 0);
        }

        @DisplayName("[경계값 분석] 재고 5에서 6을 차감하면 재고 부족으로 거절하고, 재고는 그대로다.")
        @Test
        void throwsInsufficientStock_andKeepsStock() {
            // arrange
            Product product = new Product(1L, "상품", 1_000L);
            product.changeStock(5);

            // act
            CoreException result = assertThrows(
                CoreException.class, () -> product.decreaseStock(6));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK),
                () -> assertStockQuantity(product, 5)
            );
        }
    }

    private static void assertStockQuantity(Product product, int expectedQuantity) {
        assertThat(product.getStock())
            .isNotNull()
            .extracting(Stock::quantity)
            .isEqualTo(expectedQuantity);
    }
}

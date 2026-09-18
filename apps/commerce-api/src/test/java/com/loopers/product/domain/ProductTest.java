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

    @DisplayName("[R-ADMIN-05] 상품은 존재하며 삭제되지 않은 브랜드를 참조해야 한다.")
    @Nested
    class RequiredBrand {

        @DisplayName("[동등 클래스 분할] 브랜드 식별자가 있으면 상품을 만들 수 있다.")
        @Test
        void createsProduct_whenBrandIdIsProvided() {
            Product product = new Product(1L, "상품", 1_000L);

            assertThat(product.getBrandId()).isEqualTo(1L);
        }

    }

    @DisplayName("[R-ADMIN-06] 상품의 이름과 가격은 정해진 유효 범위를 만족해야 한다.")
    @Nested
    class ValidNameAndPrice {

        @DisplayName("[경계값 분석] 이름 길이 1, 100과 가격 1원, 10억원은 허용한다.")
        @Test
        void createsProduct_whenNameAndPriceAreOnBoundary() {
            assertAll(
                () -> assertDoesNotThrow(() -> new Product(1L, "a", 1L)),
                () -> assertDoesNotThrow(() -> new Product(1L, "a".repeat(100), 1_000_000_000L))
            );
        }

        @DisplayName("[경계값 분석] 이름이 비었거나 101자이면 상품 이름 오류로 거절한다.")
        @ParameterizedTest
        @ValueSource(strings = {"", "   ",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
        void throwsInvalidProductName_whenNameIsOutsideRange(String name) {
            CoreException result = assertThrows(
                CoreException.class,
                () -> new Product(1L, name, 1_000L)
            );

            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INVALID_PRODUCT_NAME);
        }

        @DisplayName("[경계값 분석] 가격이 0원 또는 10억원 초과이면 상품 가격 오류로 거절한다.")
        @ParameterizedTest
        @ValueSource(longs = {0L, 1_000_000_001L})
        void throwsInvalidProductPrice_whenPriceIsOutsideRange(long price) {
            CoreException result = assertThrows(
                CoreException.class,
                () -> new Product(1L, "상품", price)
            );

            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INVALID_PRODUCT_PRICE);
        }
    }

    @DisplayName("[P-ADMIN-02] 상품 이름은 앞뒤 공백을 빼고 저장하며 대소문자를 구분한다.")
    @Nested
    class NormalizeName {

        @DisplayName("[동등 클래스 분할] 앞뒤 공백을 제거하고 입력한 대소문자는 유지한다.")
        @Test
        void trimsName_andKeepsCase() {
            Product product = new Product(1L, "  Nike  ", 1_000L);

            assertThat(product.getName()).isEqualTo("Nike");
        }
    }

    @DisplayName("[P-ADMIN-03] 상품 가격은 1원 이상 10억원 이하이다.")
    @Nested
    class PriceRange {

        @DisplayName("[경계값 분석] 가격 범위 바로 안과 밖을 구분한다.")
        @Test
        void distinguishesValidAndInvalidPriceBoundary() {
            assertAll(
                () -> assertDoesNotThrow(() -> new Product(1L, "최소가", 1L)),
                () -> assertDoesNotThrow(() -> new Product(1L, "최대가", 1_000_000_000L)),
                () -> assertThat(assertThrows(CoreException.class,
                    () -> new Product(1L, "최소 미만", 0L)).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_PRODUCT_PRICE),
                () -> assertThat(assertThrows(CoreException.class,
                    () -> new Product(1L, "최대 초과", 1_000_000_001L)).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_PRODUCT_PRICE)
            );
        }
    }

    @DisplayName("[R-ADMIN-07] 상품을 수정해도 소속 브랜드는 유지된다.")
    @Nested
    class KeepBrandOnUpdate {

        @DisplayName("[상태 전이] 같은 브랜드로 수정하면 이름과 가격만 바뀐다.")
        @Test
        void updatesNameAndPrice_andKeepsBrand() {
            Product product = new Product(1L, "수정 전", 1_000L);

            product.update("수정 후", 2_000L, 1L);

            assertAll(
                () -> assertThat(product.getName()).isEqualTo("수정 후"),
                () -> assertThat(product.getPrice()).isEqualTo(2_000L),
                () -> assertThat(product.getBrandId()).isEqualTo(1L)
            );
        }
    }

    @DisplayName("[P-ADMIN-04] 상품 수정에서 브랜드 변경을 시도하면 요청 전체를 거절한다.")
    @Nested
    class RejectBrandChange {

        @DisplayName("[상태 전이] 다른 브랜드로 수정하면 거절하고 기존 값은 그대로다.")
        @Test
        void throwsBrandChangeNotAllowed_andKeepsProduct() {
            Product product = new Product(1L, "수정 전", 1_000L);

            CoreException result = assertThrows(
                CoreException.class,
                () -> product.update("수정 후", 2_000L, 2L)
            );

            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.BRAND_CHANGE_NOT_ALLOWED),
                () -> assertThat(product.getName()).isEqualTo("수정 전"),
                () -> assertThat(product.getPrice()).isEqualTo(1_000L),
                () -> assertThat(product.getBrandId()).isEqualTo(1L)
            );
        }
    }

    @DisplayName("[P-ADMIN-05] 상품은 재고 0에서 시작하고 재고는 별도 기능으로만 바뀐다.")
    @Nested
    class InitialStock {

        @DisplayName("[상태 전이] 생성과 일반 수정은 재고를 0으로 유지한다.")
        @Test
        void startsWithZeroStock_andUpdateKeepsStock() {
            Product product = new Product(1L, "상품", 1_000L);

            product.update("수정 상품", 2_000L, 1L);

            assertStockQuantity(product, 0);
        }
    }

    @DisplayName("[R-ADMIN-08] 관리자는 상품의 최종 재고 수량을 0 이상으로 설정할 수 있다.")
    @Nested
    class ChangeStock {

        @DisplayName("[경계값 분석] 재고를 0과 1로 설정할 수 있다.")
        @ParameterizedTest
        @ValueSource(ints = {0, 1})
        void changesStock_whenQuantityIsNonNegative(int quantity) {
            Product product = new Product(1L, "상품", 1_000L);

            product.changeStock(quantity);

            assertStockQuantity(product, quantity);
        }

        @DisplayName("[경계값 분석] 재고를 -1로 설정하면 거절하고 기존 재고는 그대로다.")
        @Test
        void throwsInvalidStockQuantity_andKeepsStock() {
            Product product = new Product(1L, "상품", 1_000L);

            CoreException result = assertThrows(CoreException.class, () -> product.changeStock(-1));

            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INVALID_STOCK_QUANTITY),
                () -> assertStockQuantity(product, 0)
            );
        }
    }

    @DisplayName("[R-ADMIN-13] 삭제된 상품은 수정하거나 재고를 변경할 수 없다.")
    @Nested
    class RejectChangeAfterDeletion {

        @DisplayName("[상태 전이] 삭제된 상품의 수정과 재고 변경을 거절하고 상태를 유지한다.")
        @Test
        void rejectsUpdateAndStockChange_whenDeleted() {
            Product product = new Product(1L, "상품", 1_000L);
            product.delete();

            CoreException updateResult = assertThrows(
                CoreException.class,
                () -> product.update("수정 상품", 2_000L, 1L)
            );
            CoreException stockResult = assertThrows(
                CoreException.class,
                () -> product.changeStock(10)
            );

            assertAll(
                () -> assertThat(updateResult.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND),
                () -> assertThat(stockResult.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND),
                () -> assertThat(product.getName()).isEqualTo("상품"),
                () -> assertThat(product.getPrice()).isEqualTo(1_000L),
                () -> assertStockQuantity(product, 0),
                () -> assertThat(product.isDeleted()).isTrue()
            );
        }
    }

    @DisplayName("[R-ADMIN-14] 상품을 삭제해도 기존 브랜드 참조는 유지된다.")
    @Nested
    class KeepReferenceAfterDeletion {

        @DisplayName("[상태 전이] 삭제하면 삭제 여부만 바뀌고 브랜드 식별자는 그대로다.")
        @Test
        void keepsBrandId_whenDeleted() {
            Product product = new Product(1L, "상품", 1_000L);

            product.delete();

            assertAll(
                () -> assertThat(product.isDeleted()).isTrue(),
                () -> assertThat(product.getBrandId()).isEqualTo(1L)
            );
        }
    }

    @DisplayName("[R-ORDER-07] 삭제된 상품의 재고는 주문 확정에서 차감할 수 없다.")
    @Nested
    class RejectDecreaseAfterDeletion {

        @DisplayName("[상태 전이] 삭제된 상품의 재고 차감을 거절하고 재고를 유지한다.")
        @Test
        void throwsProductNotFound_andKeepsStock() {
            Product product = new Product(1L, "상품", 1_000L);
            product.changeStock(5);
            product.delete();

            CoreException result = assertThrows(CoreException.class, () -> product.decreaseStock(1));

            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND),
                () -> assertStockQuantity(product, 5)
            );
        }
    }

    @DisplayName("[R-ORDER-08] 현재 재고 이하의 수량만 상품 재고에서 차감할 수 있다.")
    @Nested
    class DecreaseStock {

        @DisplayName("[경계값 분석] 재고 5에서 5를 차감하면 0이 된다.")
        @Test
        void decreasesStock_whenQuantityEqualsStock() {
            Product product = new Product(1L, "상품", 1_000L);
            product.changeStock(5);

            product.decreaseStock(5);

            assertStockQuantity(product, 0);
        }
    }

    @DisplayName("[P-ADMIN-06] 이미 삭제된 상품을 다시 삭제하면 없는 대상으로 거절한다.")
    @Nested
    class RejectRepeatedDeletion {

        @DisplayName("[상태 전이] 재삭제를 거절하고 최초 삭제 상태를 유지한다.")
        @Test
        void throwsProductNotFound_whenAlreadyDeleted() {
            Product product = new Product(1L, "상품", 1_000L);
            product.delete();
            var deletedAt = product.getDeletedAt();

            CoreException result = assertThrows(CoreException.class, product::delete);

            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND),
                () -> assertThat(product.getDeletedAt()).isEqualTo(deletedAt)
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

package com.loopers.domain.product;

import com.loopers.domain.common.Money;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("Product 는 상품 정보와 재고 변경 규칙을 책임진다.")
class ProductModelTest {

    private static final Long BRAND_ID = 1L;
    private static final long MAX_PRICE = 100_000_000L;

    private ProductModel product(long price) {
        return ProductModel.create(BRAND_ID, "티셔츠", price);
    }

    @DisplayName("등록")
    @Nested
    class Create {
        @DisplayName("초기 재고 0 인 상품을 만들고 이름의 앞뒤 공백을 제거해 저장한다.")
        @Test
        void createsWithZeroStock() {
            ProductModel created = ProductModel.create(BRAND_ID, "  티셔츠  ", 19_900L);

            assertAll(
                () -> assertThat(created.getBrandId()).isEqualTo(BRAND_ID),
                () -> assertThat(created.getName()).isEqualTo("티셔츠"),
                () -> assertThat(created.getPrice()).isEqualTo(Money.of(19_900L)),
                () -> assertThat(created.getStockQuantity()).isZero(),
                () -> assertThat(created.isDeleted()).isFalse()
            );
        }

        @DisplayName("이름이 비었거나 100자를 넘으면 INVALID_PRODUCT_NAME 으로 거절한다.")
        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "   "})
        void rejectsBlankName(String name) {
            assertThatThrownBy(() -> ProductModel.create(BRAND_ID, name, 19_900L))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INVALID_PRODUCT_NAME);
        }

        @DisplayName("이름이 100자를 넘으면 거절하고 100자는 허용한다.")
        @Test
        void checksNameLengthBoundary() {
            String maxLength = "가".repeat(100);
            String tooLong = "가".repeat(101);

            assertThat(ProductModel.create(BRAND_ID, maxLength, 19_900L).getName()).isEqualTo(maxLength);
            assertThatThrownBy(() -> ProductModel.create(BRAND_ID, tooLong, 19_900L))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INVALID_PRODUCT_NAME);
        }

        @DisplayName("가격이 허용 범위를 벗어나면 Money 의 음수 규칙이 아니라 INVALID_PRODUCT_PRICE 로 거절한다.")
        @ParameterizedTest
        @ValueSource(longs = {0L, -1L, MAX_PRICE + 1L})
        void rejectsPriceOutOfRange(long price) {
            assertThatThrownBy(() -> ProductModel.create(BRAND_ID, "티셔츠", price))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INVALID_PRODUCT_PRICE);
        }

        @DisplayName("가격의 경계값 1원과 1억원은 허용한다.")
        @Test
        void allowsPriceBoundary() {
            assertAll(
                () -> assertThat(product(1L).getPrice()).isEqualTo(Money.of(1L)),
                () -> assertThat(product(MAX_PRICE).getPrice()).isEqualTo(Money.of(MAX_PRICE))
            );
        }
    }

    @DisplayName("수정")
    @Nested
    class Update {
        @DisplayName("이름과 가격을 바꾸고 브랜드는 유지한다.")
        @Test
        void updatesNameAndPrice() {
            ProductModel target = product(19_900L);

            target.update("맨투맨", 29_900L);

            assertAll(
                () -> assertThat(target.getName()).isEqualTo("맨투맨"),
                () -> assertThat(target.getPrice()).isEqualTo(Money.of(29_900L)),
                () -> assertThat(target.getBrandId()).isEqualTo(BRAND_ID)
            );
        }

        @DisplayName("잘못된 가격으로 수정하면 거절하고 기존 값을 유지한다.")
        @Test
        void keepsValuesOnInvalidPrice() {
            ProductModel target = product(19_900L);

            assertThatThrownBy(() -> target.update("맨투맨", 0L))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INVALID_PRODUCT_PRICE);
            assertAll(
                () -> assertThat(target.getName()).isEqualTo("티셔츠"),
                () -> assertThat(target.getPrice()).isEqualTo(Money.of(19_900L))
            );
        }
    }

    @DisplayName("재고")
    @Nested
    class StockBehavior {
        @DisplayName("최종 수량으로 재고를 변경한다.")
        @Test
        void changesStock() {
            ProductModel target = product(19_900L);
            target.changeStock(5L);

            StockChange change = target.changeStock(2L);

            assertAll(
                () -> assertThat(target.getStockQuantity()).isEqualTo(2L),
                () -> assertThat(change.beforeQuantity()).isEqualTo(5L),
                () -> assertThat(change.afterQuantity()).isEqualTo(2L)
            );
        }

        @DisplayName("재고 5 에서 2 를 차감하면 3 이 남는다.")
        @Test
        void decreasesStock() {
            ProductModel target = product(19_900L);
            target.changeStock(5L);

            StockChange change = target.decreaseStock(2L);

            assertAll(
                () -> assertThat(target.getStockQuantity()).isEqualTo(3L),
                () -> assertThat(change.changedQuantity()).isEqualTo(2L)
            );
        }

        @DisplayName("재고 5 에서 6 을 차감하려 하면 거절하고 재고를 유지한다.")
        @Test
        void rejectsDecreaseOverStock() {
            ProductModel target = product(19_900L);
            target.changeStock(5L);

            assertThatThrownBy(() -> target.decreaseStock(6L))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INSUFFICIENT_STOCK);
            assertThat(target.getStockQuantity()).isEqualTo(5L);
        }
    }

    @DisplayName("삭제")
    @Nested
    class Delete {
        @DisplayName("삭제하면 삭제 상태가 된다.")
        @Test
        void marksAsDeleted() {
            ProductModel target = product(19_900L);

            target.delete();

            assertThat(target.isDeleted()).isTrue();
        }
    }
}

package com.loopers.domain.catalog;

import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.loopers.support.ErrorAssertions.assertThrowsErrorType;
import static org.assertj.core.api.Assertions.assertThat;

/** AG-03 상품. INV-03, INV-11, INV-13, ST-02. */
class ProductModelTest {

    @DisplayName("[INV-13][ASM-04] 이름 200자, 가격 0원, 재고 0 은 허용된다.")
    @Test
    void create_succeeds_withBoundaryValues() {
        ProductModel product = new ProductModel(1L, "a".repeat(200), 0L, 0);

        assertThat(product.getBrandId()).isEqualTo(1L);
        assertThat(product.getPrice()).isZero();
        assertThat(product.getStock()).isZero();
        assertThat(product.isDeleted()).isFalse();
    }

    @DisplayName("[INV-13][ER-19 INVALID_PRODUCT_NAME] 이름 누락·빈 값은 거부된다.")
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void create_throwsInvalidName_whenBlank(String name) {
        assertThrowsErrorType(() -> new ProductModel(1L, name, 1000L, 1), ErrorType.INVALID_PRODUCT_NAME);
    }

    @DisplayName("[INV-13][ER-19 INVALID_PRODUCT_NAME] 이름 201자는 거부된다.")
    @Test
    void create_throwsInvalidName_whenTooLong() {
        assertThrowsErrorType(() -> new ProductModel(1L, "a".repeat(201), 1000L, 1), ErrorType.INVALID_PRODUCT_NAME);
    }

    @DisplayName("[INV-13][ER-20 INVALID_PRODUCT_PRICE] 가격 누락·음수는 거부된다.")
    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {-1L, Long.MIN_VALUE})
    void create_throwsInvalidPrice_whenNegativeOrNull(Long price) {
        assertThrowsErrorType(() -> new ProductModel(1L, "상품", price, 1), ErrorType.INVALID_PRODUCT_PRICE);
    }

    @DisplayName("[INV-03][ER-21 INVALID_STOCK] 재고 누락·음수는 거부된다 (ASM-17).")
    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1})
    void create_throwsInvalidStock_whenNegativeOrNull(Integer stock) {
        assertThrowsErrorType(() -> new ProductModel(1L, "상품", 1000L, stock), ErrorType.INVALID_STOCK);
    }

    @DisplayName("[INV-11] 브랜드 없이 상품을 만들 수 없다.")
    @Test
    void create_throwsBrandNotFound_whenBrandIdNull() {
        assertThrowsErrorType(() -> new ProductModel(null, "상품", 1000L, 1), ErrorType.BRAND_NOT_FOUND);
    }

    @DisplayName("[INV-11][ASM-16] 수정은 이름·가격만 바꾸고 브랜드·재고는 그대로다.")
    @Test
    void update_changesNameAndPriceOnly() {
        ProductModel product = new ProductModel(7L, "상품", 1000L, 5);

        product.update("새 이름", 2000L);

        assertThat(product.getName()).isEqualTo("새 이름");
        assertThat(product.getPrice()).isEqualTo(2000L);
        assertThat(product.getBrandId()).isEqualTo(7L);
        assertThat(product.getStock()).isEqualTo(5);
    }

    @DisplayName("[INV-13] 수정 실패 시 기존 값 유지.")
    @Test
    void update_keepsOldValues_whenInvalid() {
        ProductModel product = new ProductModel(7L, "상품", 1000L, 5);

        assertThrowsErrorType(() -> product.update("새 이름", -1L), ErrorType.INVALID_PRODUCT_PRICE);

        assertThat(product.getName()).isEqualTo("상품");
        assertThat(product.getPrice()).isEqualTo(1000L);
    }

    @DisplayName("[INV-03] 재고 변경은 증감이 아니라 최종 수량 설정이다.")
    @Test
    void changeStock_setsFinalQuantity() {
        ProductModel product = new ProductModel(7L, "상품", 1000L, 5);

        product.changeStock(30);

        assertThat(product.getStock()).isEqualTo(30);
    }

    @DisplayName("[INV-03][ER-21 INVALID_STOCK] 재고를 음수·null 로 설정하면 거부되고 기존 값 유지.")
    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-5})
    void changeStock_throwsInvalidStock_whenNegative(Integer stock) {
        ProductModel product = new ProductModel(7L, "상품", 1000L, 5);

        assertThrowsErrorType(() -> product.changeStock(stock), ErrorType.INVALID_STOCK);

        assertThat(product.getStock()).isEqualTo(5);
    }

    @DisplayName("[INV-03] 재고 차감은 수량만큼 줄인다. 정확히 재고만큼도 가능하다.")
    @Test
    void deductStock_reducesStock() {
        ProductModel product = new ProductModel(7L, "상품", 1000L, 5);

        product.deductStock(5);

        assertThat(product.getStock()).isZero();
    }

    @DisplayName("[INV-03][ER-16 INSUFFICIENT_STOCK] 재고 < 수량이면 거부되고 재고 유지. message 에 productId.")
    @Test
    void deductStock_throwsInsufficientStock_whenNotEnough() {
        ProductModel product = new ProductModel(7L, "상품", 1000L, 5);

        assertThrowsErrorType(() -> product.deductStock(6), ErrorType.INSUFFICIENT_STOCK);

        assertThat(product.getStock()).isEqualTo(5);
    }

    @DisplayName("[ST-02] delete() 로 ACTIVE → DELETED.")
    @Test
    void delete_marksDeleted() {
        ProductModel product = new ProductModel(7L, "상품", 1000L, 5);

        product.delete();

        assertThat(product.isDeleted()).isTrue();
    }
}

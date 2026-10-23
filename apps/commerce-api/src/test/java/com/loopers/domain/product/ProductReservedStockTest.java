package com.loopers.domain.product;

import com.loopers.domain.brand.Brand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

class ProductReservedStockTest {

    @Test
    void restoresReservedQuantityToTheCurrentAvailableStock() {
        Product product = productWithStock(5);

        product.deductStock(2);
        assertThat(product.getStockQuantity()).isEqualTo(3);

        product.returnReservedStock(2);

        assertThat(product.getStockQuantity()).isEqualTo(5);
    }

    @Test
    void addsReturnedQuantityInsteadOfRestoringTheOldStockSnapshot() {
        Product product = productWithStock(5);
        product.deductStock(2);
        product.changeStockQuantityTo(10);

        product.returnReservedStock(2);

        assertThat(product.getStockQuantity()).isEqualTo(12);
    }

    @Test
    void validatesAStockReturnWithoutChangingItsQuantity() {
        ProductStock stock = new ProductStock(3);

        stock.validateReturn(2);

        assertThat(stock.getQuantity()).isEqualTo(3);
    }

    @Test
    void validatesAProductReturnWithoutChangingItsQuantity() {
        Product product = productWithStock(3);

        product.validateReservedStockReturn(2);

        assertThat(product.getStockQuantity()).isEqualTo(3);
    }

    @ParameterizedTest
    @CsvSource({"0, 2147483647", "2147483645, 2", "2147483646, 1"})
    void acceptsAStockReturnAtTheExactStorageLimit(int available, int returned) {
        ProductStock stock = new ProductStock(available);

        stock.validateReturn(returned);
        assertThat(stock.getQuantity()).isEqualTo(available);
        stock.returnReserved(returned);

        assertThat(stock.getQuantity()).isEqualTo(Integer.MAX_VALUE);
    }

    @ParameterizedTest
    @CsvSource({"2147483647, 1", "2147483646, 2", "1, 2147483647"})
    void rejectsOverflowBeforeChangingStock(int available, int returned) {
        ProductStock stock = new ProductStock(available);

        assertAll(
            () -> assertThatThrownBy(() -> stock.validateReturn(returned))
                .isInstanceOf(IllegalStateException.class),
            () -> assertThat(stock.getQuantity()).isEqualTo(available),
            () -> assertThatThrownBy(() -> stock.returnReserved(returned))
                .isInstanceOf(IllegalStateException.class),
            () -> assertThat(stock.getQuantity()).isEqualTo(available)
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsNonPositiveReturnsWithoutChangingStock(int returned) {
        ProductStock stock = new ProductStock(3);

        assertAll(
            () -> assertThatThrownBy(() -> stock.validateReturn(returned))
                .isInstanceOf(IllegalArgumentException.class),
            () -> assertThat(stock.getQuantity()).isEqualTo(3),
            () -> assertThatThrownBy(() -> stock.returnReserved(returned))
                .isInstanceOf(IllegalArgumentException.class),
            () -> assertThat(stock.getQuantity()).isEqualTo(3)
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void productPreservesTheSameReturnInputBoundary(int returned) {
        Product product = productWithStock(3);

        assertAll(
            () -> assertThatThrownBy(() -> product.validateReservedStockReturn(returned))
                .isInstanceOf(IllegalArgumentException.class),
            () -> assertThatThrownBy(() -> product.returnReservedStock(returned))
                .isInstanceOf(IllegalArgumentException.class),
            () -> assertThat(product.getStockQuantity()).isEqualTo(3)
        );
    }

    @Test
    void productPreservesTheSameOverflowBoundary() {
        Product product = productWithStock(Integer.MAX_VALUE);

        assertAll(
            () -> assertThatThrownBy(() -> product.validateReservedStockReturn(1))
                .isInstanceOf(IllegalStateException.class),
            () -> assertThatThrownBy(() -> product.returnReservedStock(1))
                .isInstanceOf(IllegalStateException.class),
            () -> assertThat(product.getStockQuantity()).isEqualTo(Integer.MAX_VALUE)
        );
    }

    @Test
    void returnsStockToADeletedProductWithoutRestoringItsVisibility() {
        Product product = productWithStock(5);
        product.deductStock(2);
        ZonedDateTime deletedAt = ZonedDateTime.parse("2026-10-23T00:00:00Z");
        product.delete(deletedAt);

        product.validateReservedStockReturn(2);
        assertThat(product.getStockQuantity()).isEqualTo(3);
        product.returnReservedStock(2);

        assertAll(
            () -> assertThat(product.getStockQuantity()).isEqualTo(5),
            () -> assertThat(product.isDeleted()).isTrue(),
            () -> assertThat(product.getDeletedAt()).isEqualTo(deletedAt),
            () -> assertThatThrownBy(() -> product.deductStock(1))
                .isInstanceOfSatisfying(ProductException.class,
                    error -> assertThat(error.getReason()).isEqualTo(ProductException.Reason.DELETED_PRODUCT)),
            () -> assertThatThrownBy(() -> product.validateStockDeduction(1))
                .isInstanceOfSatisfying(ProductException.class,
                    error -> assertThat(error.getReason()).isEqualTo(ProductException.Reason.DELETED_PRODUCT)),
            () -> assertThatThrownBy(() -> product.changeStockQuantityTo(10))
                .isInstanceOfSatisfying(ProductException.class,
                    error -> assertThat(error.getReason()).isEqualTo(ProductException.Reason.DELETED_PRODUCT)),
            () -> assertThat(product.getStockQuantity()).isEqualTo(5)
        );
    }

    private Product productWithStock(int quantity) {
        return new Product(new Brand("브랜드"), "상품", 1000, quantity);
    }
}

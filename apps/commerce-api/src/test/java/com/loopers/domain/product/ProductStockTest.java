package com.loopers.domain.product;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

class ProductStockTest {

    @DisplayName("STOCK-SET-01: 재고 10을 3으로 변경하면 최종 재고는 3이다.")
    @Test
    void changesStockToRequestedQuantity() {
        ProductStock stock = new ProductStock(10);

        stock.changeQuantityTo(3);

        assertThat(stock.getQuantity()).isEqualTo(3);
    }

    @DisplayName("STOCK-SET-02: 재고를 0으로 변경할 수 있다.")
    @Test
    void allowsChangingStockToZero() {
        ProductStock stock = new ProductStock(10);

        stock.changeQuantityTo(0);

        assertThat(stock.getQuantity()).isZero();
    }

    @DisplayName("STOCK-SET-03: 음수로 재고 변경을 요청하면 거절하고 기존 재고를 유지한다.")
    @ParameterizedTest
    @ValueSource(ints = {-1, Integer.MIN_VALUE})
    void rejectsNegativeStockChange(int quantity) {
        ProductStock stock = new ProductStock(10);

        assertAll(
            () -> assertThatThrownBy(() -> stock.changeQuantityTo(quantity))
                .isInstanceOfSatisfying(ProductStockException.class,
                    error -> assertThat(error.getReason())
                        .isEqualTo(ProductStockException.Reason.INVALID_STOCK_QUANTITY)),
            () -> assertThat(stock.getQuantity()).isEqualTo(10)
        );
    }

    @DisplayName("STOCK-SET-04: 기존 수량 이상부터 Integer 최댓값까지 최종 재고로 설정할 수 있다.")
    @ParameterizedTest
    @ValueSource(ints = {10, 20, Integer.MAX_VALUE})
    void allowsChangingStockToSameOrGreaterQuantity(int quantity) {
        ProductStock stock = new ProductStock(10);

        stock.changeQuantityTo(quantity);

        assertThat(stock.getQuantity()).isEqualTo(quantity);
    }

    @DisplayName("STOCK-SET-05: 재고 설정과 차감은 같은 현재 수량을 변경한다.")
    @Test
    void changesAndDeductsTheSameStock() {
        ProductStock stock = new ProductStock(10);

        stock.changeQuantityTo(3);
        stock.deduct(2);
        assertThat(stock.getQuantity()).isEqualTo(1);

        stock.changeQuantityTo(8);
        assertThat(stock.getQuantity()).isEqualTo(8);

        stock.deduct(8);
        assertThat(stock.getQuantity()).isZero();
    }

    @DisplayName("STOCK-01: 재고 5에서 2개를 차감하면 3개가 남는다.")
    @Test
    void deductsRequestedQuantity() {
        ProductStock stock = new ProductStock(5);

        stock.deduct(2);

        assertThat(stock.getQuantity()).isEqualTo(3);
    }

    @DisplayName("STOCK-02: 재고보다 많은 수량은 거절하고 기존 재고를 유지한다.")
    @Test
    void rejectsDeductionExceedingStock() {
        ProductStock stock = new ProductStock(5);

        assertDeductionRejected(stock, 6, ProductStockException.Reason.INSUFFICIENT_STOCK, 5);
    }

    @DisplayName("STOCK-03: 남은 재고를 모두 차감하면 0개가 된다.")
    @Test
    void allowsDeductingAllStock() {
        ProductStock stock = new ProductStock(5);

        stock.deduct(5);

        assertThat(stock.getQuantity()).isZero();
    }

    @DisplayName("STOCK-04: 재고가 0이면 차감을 거절하고 0을 유지한다.")
    @Test
    void rejectsDeductionWhenOutOfStock() {
        ProductStock stock = new ProductStock(0);

        assertDeductionRejected(stock, 1, ProductStockException.Reason.INSUFFICIENT_STOCK, 0);
    }

    @DisplayName("STOCK-05: 0 또는 음수 차감은 거절하고 기존 재고를 유지한다.")
    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsNonPositiveDeduction(int quantity) {
        ProductStock stock = new ProductStock(5);

        assertDeductionRejected(stock, quantity, ProductStockException.Reason.INVALID_DEDUCTION_QUANTITY, 5);
    }

    @DisplayName("초기 재고가 음수인 객체는 생성할 수 없다.")
    @ParameterizedTest
    @ValueSource(ints = {-1, Integer.MIN_VALUE})
    void rejectsNegativeInitialStock(int quantity) {
        assertThatThrownBy(() -> new ProductStock(quantity))
            .isInstanceOfSatisfying(ProductStockException.class,
                error -> assertThat(error.getReason())
                    .isEqualTo(ProductStockException.Reason.INVALID_STOCK_QUANTITY));
    }

    @DisplayName("내부 정수 표현의 최댓값 재고도 전량 차감할 수 있다.")
    @Test
    void allowsDeductingMaximumRepresentableStock() {
        ProductStock stock = new ProductStock(Integer.MAX_VALUE);

        stock.deduct(Integer.MAX_VALUE);

        assertThat(stock.getQuantity()).isZero();
    }

    @DisplayName("차감 성공 후 부족으로 실패해도 남은 재고를 다시 사용할 수 있다.")
    @Test
    void preservesPreviousDeductionWhenNextDeductionFails() {
        ProductStock stock = new ProductStock(5);
        stock.deduct(2);

        assertDeductionRejected(stock, 4, ProductStockException.Reason.INSUFFICIENT_STOCK, 3);
        stock.deduct(3);

        assertThat(stock.getQuantity()).isZero();
    }

    private void assertDeductionRejected(
        ProductStock stock,
        int deductionQuantity,
        ProductStockException.Reason expectedReason,
        int expectedQuantity
    ) {
        assertAll(
            () -> assertThatThrownBy(() -> stock.deduct(deductionQuantity))
                .isInstanceOfSatisfying(ProductStockException.class,
                    error -> assertThat(error.getReason()).isEqualTo(expectedReason)),
            () -> assertThat(stock.getQuantity()).isEqualTo(expectedQuantity)
        );
    }
}

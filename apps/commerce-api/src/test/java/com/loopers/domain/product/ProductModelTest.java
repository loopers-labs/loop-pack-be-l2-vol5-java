package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductModelTest {

    @DisplayName("재고 5에서 2를 차감하면, 재고 3이 남는다.")
    @Test
    void deductsStock_whenQuantityIsAvailable() {
        // arrange
        ProductModel product = new ProductModel(1L, "상품", 10_000L, 5);

        // act
        product.deductStock(2);

        // assert
        assertThat(product.getStock()).isEqualTo(3);
    }

    @DisplayName("재고 5에서 6 차감을 요청하면, 거절하고 재고는 5를 유지한다.")
    @Test
    void rejectsDeduction_whenQuantityExceedsStock() {
        // arrange
        ProductModel product = new ProductModel(1L, "상품", 10_000L, 5);

        // act & assert
        assertThatThrownBy(() -> product.deductStock(6))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
        assertThat(product.getStock()).isEqualTo(5);
    }

    @DisplayName("차감 수량이 0 이하이면, 거절하고 재고를 유지한다.")
    @Test
    void rejectsDeduction_whenQuantityIsNotPositive() {
        // arrange
        ProductModel product = new ProductModel(1L, "상품", 10_000L, 5);

        // act & assert
        assertThatThrownBy(() -> product.deductStock(0))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
        assertThatThrownBy(() -> product.deductStock(-1))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
        assertThat(product.getStock()).isEqualTo(5);
    }

    @DisplayName("재고 5에서 2를 예약하면, 가용 재고는 3이고 총 재고는 5다.")
    @Test
    void reservesStock_reducingAvailableOnly() {
        ProductModel product = new ProductModel(1L, "상품", 10_000L, 5);

        product.reserve(2);

        assertThat(product.getAvailableStock()).isEqualTo(3);
        assertThat(product.getStock()).isEqualTo(5);
        assertThat(product.getReservedStock()).isEqualTo(2);
    }

    @DisplayName("가용 재고를 초과하는 예약은 거절한다.")
    @Test
    void rejectsReservation_whenExceedsAvailableStock() {
        ProductModel product = new ProductModel(1L, "상품", 10_000L, 3);

        assertThatThrownBy(() -> product.reserve(4))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
        assertThat(product.getReservedStock()).isEqualTo(0);
    }

    @DisplayName("예약 소비 시 총 재고와 예약 재고가 함께 차감된다.")
    @Test
    void consumeReservation_decreasesBothStockAndReserved() {
        ProductModel product = new ProductModel(1L, "상품", 10_000L, 5);
        product.reserve(2);

        product.consumeReservation(2);

        assertThat(product.getStock()).isEqualTo(3);
        assertThat(product.getReservedStock()).isEqualTo(0);
        assertThat(product.getAvailableStock()).isEqualTo(3);
    }

    @DisplayName("예약 반환 시 예약 재고만 차감되고 총 재고는 유지된다.")
    @Test
    void releaseReservation_decreasesOnlyReserved() {
        ProductModel product = new ProductModel(1L, "상품", 10_000L, 5);
        product.reserve(2);

        product.releaseReservation(2);

        assertThat(product.getStock()).isEqualTo(5);
        assertThat(product.getReservedStock()).isEqualTo(0);
        assertThat(product.getAvailableStock()).isEqualTo(5);
    }

    @DisplayName("가용 재고 설정 시 예약 수량은 보존되고 총 재고가 재계산된다.")
    @Test
    void changeStock_preservesReservationAndRecalculatesTotal() {
        ProductModel product = new ProductModel(1L, "상품", 10_000L, 10);
        product.reserve(3);

        product.changeStock(7);

        assertThat(product.getAvailableStock()).isEqualTo(7);
        assertThat(product.getReservedStock()).isEqualTo(3);
        assertThat(product.getStock()).isEqualTo(10);
    }
}

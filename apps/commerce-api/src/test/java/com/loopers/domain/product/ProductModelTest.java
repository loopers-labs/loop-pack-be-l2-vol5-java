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
        ProductModel product = new ProductModel("상품", 10_000L, 5);

        // act
        product.deductStock(2);

        // assert
        assertThat(product.getStock()).isEqualTo(3);
    }

    @DisplayName("재고 5에서 6 차감을 요청하면, 거절하고 재고는 5를 유지한다.")
    @Test
    void rejectsDeduction_whenQuantityExceedsStock() {
        // arrange
        ProductModel product = new ProductModel("상품", 10_000L, 5);

        // act & assert
        assertThatThrownBy(() -> product.deductStock(6))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
        assertThat(product.getStock()).isEqualTo(5);
    }
}

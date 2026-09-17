package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StockQuantityTest {

    @DisplayName("재고 수량을 만들 때,")
    @Nested
    class Create {
        @DisplayName("0은 유효한 재고 수량이다.")
        @Test
        void createsQuantity_whenAmountIsZero() {
            // act
            StockQuantity quantity = new StockQuantity(0L);

            // assert
            assertThat(quantity.amount()).isZero();
        }

        @DisplayName("음수이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsException_whenAmountIsNegative() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                new StockQuantity(-1L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("재고를 차감할 때,")
    @Nested
    class Decrease {
        @DisplayName("재고가 충분하면, 차감한 새 재고 수량을 반환한다.")
        @Test
        void decreasesQuantity_whenStockIsSufficient() {
            // arrange
            StockQuantity quantity = new StockQuantity(5L);

            // act
            StockQuantity result = quantity.decrease(2L);

            // assert
            assertAll(
                () -> assertThat(result.amount()).isEqualTo(3L),
                () -> assertThat(quantity.amount()).isEqualTo(5L)
            );
        }

        @DisplayName("요청 수량이 재고보다 많으면, CONFLICT 예외가 발생한다.")
        @Test
        void throwsException_whenStockIsInsufficient() {
            // arrange
            StockQuantity quantity = new StockQuantity(5L);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                quantity.decrease(6L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        }

        @DisplayName("차감 수량이 0이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsException_whenDecreaseAmountIsZero() {
            // arrange
            StockQuantity quantity = new StockQuantity(5L);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                quantity.decrease(0L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }
}

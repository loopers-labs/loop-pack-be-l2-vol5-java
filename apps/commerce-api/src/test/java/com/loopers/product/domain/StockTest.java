package com.loopers.product.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StockTest {

    @DisplayName("[INV-14] 재고 수량은 0 이상이다.")
    @Nested
    class NonNegativeQuantity {

        @DisplayName("[경계값 분석] 수량 0, 1로 재고를 만들 수 있다.")
        @ParameterizedTest
        @ValueSource(ints = {0, 1})
        void createsStock_whenQuantityIsZeroOrMore(int quantity) {
            // act
            Stock result = new Stock(quantity);

            // assert
            assertThat(result.quantity()).isEqualTo(quantity);
        }

        @DisplayName("[경계값 분석] 수량 -1로 재고를 만들면 재고 수량 오류로 거절한다.")
        @Test
        void throwsInvalidStockQuantity_whenQuantityIsNegative() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> new Stock(-1));

            // assert
            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INVALID_STOCK_QUANTITY);
        }
    }

    @DisplayName("[INV-15] 차감 수량은 현재 재고 이하다.")
    @Nested
    class DecreaseWithinStock {

        @DisplayName("[경계값 분석] 재고 5에서 4, 5를 차감하면 1, 0이 남는다.")
        @ParameterizedTest
        @CsvSource({"4, 1", "5, 0"})
        void decreases_whenAmountIsWithinStock(int amount, int expected) {
            // arrange
            Stock stock = new Stock(5);

            // act
            Stock result = stock.decrease(amount);

            // assert
            assertThat(result.quantity()).isEqualTo(expected);
        }

        @DisplayName("[동등 클래스 분할] 재고 5에서 2를 차감하면 3인 새 재고가 되고, 차감 전 재고는 5 그대로다.")
        @Test
        void returnsDecreasedStock_andKeepsOriginal() {
            // arrange
            Stock stock = new Stock(5);

            // act
            Stock result = stock.decrease(2);

            // assert
            assertAll(
                () -> assertThat(result.quantity()).isEqualTo(3),
                () -> assertThat(stock.quantity()).isEqualTo(5)
            );
        }

        @DisplayName("[경계값 분석] 재고 5에서 6을 차감하면 재고 부족으로 거절하고, 재고는 5 그대로다.")
        @Test
        void throwsInsufficientStock_whenAmountExceedsStock() {
            // arrange
            Stock stock = new Stock(5);

            // act
            CoreException result = assertThrows(CoreException.class, () -> stock.decrease(6));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK),
                () -> assertThat(stock.quantity()).isEqualTo(5)
            );
        }

        @DisplayName("[경계값 분석] 재고 0에서 1을 차감하면 재고 부족으로 거절하고, 재고는 0 그대로다.")
        @Test
        void throwsInsufficientStock_whenStockIsZero() {
            // arrange
            Stock stock = new Stock(0);

            // act
            CoreException result = assertThrows(CoreException.class, () -> stock.decrease(1));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK),
                () -> assertThat(stock.quantity()).isZero()
            );
        }
    }
}

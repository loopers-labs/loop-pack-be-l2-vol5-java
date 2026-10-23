package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StockTest {

    @DisplayName("재고를 생성할 때,")
    @Nested
    class Create {
        @DisplayName("음수이면 INVALID_INPUT 으로 거절한다.")
        @Test
        void rejectsWithInvalidInput_whenRemainingIsNegative() {
            // act
            CoreException exception = assertThrows(CoreException.class, () -> new Stock(-1));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.INVALID_INPUT);
        }

        @DisplayName("0이면 품절 상태의 재고로 생성된다.")
        @Test
        void createsStock_whenRemainingIsZero() {
            // act
            Stock stock = new Stock(0);

            // assert
            assertThat(stock.remaining()).isZero();
        }
    }

    @DisplayName("재고를 차감할 때,")
    @Nested
    class Decrease {
        @DisplayName("보유 재고보다 많은 수량이면 OUT_OF_STOCK 으로 거절하고, 기존 재고는 그대로다.")
        @Test
        void rejectsWithOutOfStock_whenQuantityIsGreaterThanRemaining() {
            // arrange
            Stock stock = new Stock(5);

            // act
            CoreException exception = assertThrows(CoreException.class, () -> stock.decrease(6));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.OUT_OF_STOCK);
            assertThat(stock.remaining()).isEqualTo(5);
        }

        @DisplayName("보유 재고 이하의 수량이면 차감된 새 재고를 반환하고, 기존 재고는 그대로다.")
        @Test
        void returnsDecreasedStock_whenQuantityIsLessThanRemaining() {
            // arrange
            Stock stock = new Stock(5);

            // act
            Stock decreased = stock.decrease(2);

            // assert
            assertThat(decreased.remaining()).isEqualTo(3);
            assertThat(stock.remaining()).isEqualTo(5);
        }

        @DisplayName("보유 재고와 같은 수량이면 전량 차감되어 재고가 0이 된다.")
        @Test
        void returnsZeroStock_whenQuantityIsEqualToRemaining() {
            // arrange
            Stock stock = new Stock(5);

            // act
            Stock decreased = stock.decrease(5);

            // assert
            assertThat(decreased.remaining()).isZero();
        }

        @DisplayName("재고가 0이면 1 이상의 수량은 OUT_OF_STOCK 이고, 0 이하의 수량은 OUT_OF_STOCK 이 아니라 INVALID_INPUT 이다.")
        @Test
        void distinguishesOutOfStockFromInvalidQuantity_whenStockIsZero() {
            // arrange
            Stock soldOut = new Stock(0);

            // act
            CoreException outOfStock = assertThrows(CoreException.class, () -> soldOut.decrease(1));
            CoreException invalid = assertThrows(CoreException.class, () -> soldOut.decrease(0));

            // assert
            assertThat(outOfStock.getErrorType()).isEqualTo(ErrorType.OUT_OF_STOCK);
            assertThat(invalid.getErrorType()).isEqualTo(ErrorType.INVALID_INPUT);
            assertThat(soldOut.remaining()).isZero();
        }

        @DisplayName("0 이하의 수량이면 INVALID_INPUT 으로 거절하고, 기존 재고는 그대로다.")
        @ParameterizedTest
        @ValueSource(ints = {0, -1})
        void rejectsWithInvalidInput_whenQuantityIsNotPositive(int quantity) {
            // arrange
            Stock stock = new Stock(5);

            // act
            CoreException exception = assertThrows(CoreException.class, () -> stock.decrease(quantity));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.INVALID_INPUT);
            assertThat(stock.remaining()).isEqualTo(5);
        }
    }

    @DisplayName("요청 수량에 충분한지 확인할 때,")
    @Nested
    class IsEnoughFor {
        @DisplayName("재고가 수량 이상이면 충분하고, 수량보다 적으면 부족하다.")
        @ParameterizedTest
        @CsvSource({"5, 4, true", "5, 5, true", "5, 6, false", "0, 1, false"})
        void judgesBySufficiency(int remaining, int quantity, boolean expected) {
            assertThat(new Stock(remaining).isEnoughFor(quantity)).isEqualTo(expected);
        }

        @DisplayName("확인만 하고 재고를 바꾸지 않는다.")
        @Test
        void doesNotChangeStock() {
            // arrange
            Stock stock = new Stock(5);

            // act
            stock.isEnoughFor(3);

            // assert
            assertThat(stock.remaining()).isEqualTo(5);
        }
    }

    @DisplayName("재고를 늘릴 때,")
    @Nested
    class Increase {
        @DisplayName("수량만큼 늘어난 새 재고를 반환하고, 기존 재고는 그대로다.")
        @ParameterizedTest
        @CsvSource({"3, 2, 5", "0, 1, 1"})
        void returnsIncreasedStock_andKeepsOriginal(int remaining, int quantity, int expected) {
            // arrange
            Stock stock = new Stock(remaining);

            // act
            Stock increased = stock.increase(quantity);

            // assert
            assertThat(increased.remaining()).isEqualTo(expected);
            assertThat(stock.remaining()).isEqualTo(remaining);
        }

        @DisplayName("0 이하의 수량이면 INVALID_INPUT 으로 거절한다.")
        @ParameterizedTest
        @ValueSource(ints = {0, -1})
        void rejectsWithInvalidInput_whenQuantityIsNotPositive(int quantity) {
            // arrange
            Stock stock = new Stock(3);

            // act
            CoreException exception = assertThrows(CoreException.class, () -> stock.increase(quantity));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.INVALID_INPUT);
            assertThat(stock.remaining()).isEqualTo(3);
        }

        @DisplayName("합계가 int 범위를 넘으면 INVALID_INPUT 으로 거절한다.")
        @Test
        void rejectsWithInvalidInput_whenSumOverflows() {
            // arrange
            Stock stock = new Stock(Integer.MAX_VALUE);

            // act
            CoreException exception = assertThrows(CoreException.class, () -> stock.increase(1));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.INVALID_INPUT);
        }

        @DisplayName("차감한 수량을 그대로 늘리면 원래 재고로 돌아온다 (5 → 3 → 5).")
        @Test
        void returnsToOriginal_afterDecreaseAndIncrease() {
            assertThat(new Stock(5).decrease(2).increase(2)).isEqualTo(new Stock(5));
        }
    }
}

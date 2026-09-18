package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StockTest {

    @DisplayName("재고를 생성할 때, ")
    @Nested
    class Create {
        @DisplayName("초기 수량이 음수이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenInitialRemainingIsNegative() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> new Stock(-1));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("재고를 차감할 때, ")
    @Nested
    class Decrease {
        @DisplayName("보유 수량보다 많은 수량을 차감하면, BAD_REQUEST 예외가 발생하고 기존 재고가 유지된다.")
        @Test
        void throwsBadRequestException_whenQuantityExceedsRemaining() {
            // arrange
            Stock stock = new Stock(5);

            // act
            CoreException result = assertThrows(CoreException.class, () -> stock.decrease(6));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            assertThat(stock.getRemaining()).isEqualTo(5);
        }

        @DisplayName("0 이하의 수량을 차감하면, BAD_REQUEST 예외가 발생하고 기존 재고가 유지된다.")
        @Test
        void throwsBadRequestException_whenQuantityIsNotPositive() {
            // arrange
            Stock stock = new Stock(5);

            // act
            CoreException zeroResult = assertThrows(CoreException.class, () -> stock.decrease(0));
            CoreException negativeResult = assertThrows(CoreException.class, () -> stock.decrease(-1));

            // assert
            assertThat(zeroResult.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            assertThat(negativeResult.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            assertThat(stock.getRemaining()).isEqualTo(5);
        }

        @DisplayName("보유 수량 이하로 차감하면, 차감된 만큼 재고가 줄어든다.")
        @Test
        void decreasesRemaining_whenQuantityIsWithinStock() {
            // arrange
            Stock stock = new Stock(5);

            // act
            stock.decrease(2);

            // assert
            assertThat(stock.getRemaining()).isEqualTo(3);
        }

        @DisplayName("보유 수량 전체를 차감하면, 재고가 0이 된다.")
        @Test
        void decreasesToZero_whenQuantityEqualsRemaining() {
            // arrange
            Stock stock = new Stock(5);

            // act
            stock.decrease(5);

            // assert
            assertThat(stock.getRemaining()).isEqualTo(0);
        }
    }
}

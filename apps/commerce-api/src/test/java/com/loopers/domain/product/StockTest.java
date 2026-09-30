package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StockTest {
    @DisplayName("재고를 생성할 때, ")
    @Nested
    class Create {
        @DisplayName("0 이상의 수량이 주어지면, 정상적으로 생성된다.")
        @Test
        void createsStock_whenQuantityIsZeroOrPositive() {
            // arrange & act
            Stock stock = new Stock(5);

            // assert
            assertThat(stock.remaining()).isEqualTo(5);
        }

        @DisplayName("음수 수량이 주어지면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenQuantityIsNegative() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> new Stock(-1));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("재고를 차감할 때, ")
    @Nested
    class Decrease {
        @DisplayName("보유 수량 이하로 요청하면, 정상적으로 차감된다.")
        @Test
        void decreasesStock_whenQuantityIsWithinRemaining() {
            // arrange
            Stock stock = new Stock(5);

            // act
            stock.decrease(2);

            // assert
            assertThat(stock.remaining()).isEqualTo(3);
        }

        @DisplayName("보유 수량 전부를 요청하면, 0까지 차감된다.")
        @Test
        void decreasesToZero_whenQuantityEqualsRemaining() {
            // arrange
            Stock stock = new Stock(5);

            // act
            stock.decrease(5);

            // assert
            assertThat(stock.remaining()).isEqualTo(0);
        }

        @DisplayName("보유 수량을 초과해서 요청하면, BAD_REQUEST 예외가 발생하고 재고는 그대로 유지된다.")
        @Test
        void throwsBadRequestException_whenQuantityExceedsRemaining() {
            // arrange
            Stock stock = new Stock(5);

            // act
            CoreException result = assertThrows(CoreException.class, () -> stock.decrease(6));

            // assert
            assertAll(
                () -> assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                () -> assertThat(stock.remaining()).isEqualTo(5)
            );
        }

        @DisplayName("0 이하의 수량을 요청하면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenQuantityIsZeroOrNegative() {
            // arrange
            Stock stock = new Stock(5);

            // act
            CoreException result = assertThrows(CoreException.class, () -> stock.decrease(0));

            // assert
            assertAll(
                () -> assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                () -> assertThat(stock.remaining()).isEqualTo(5)
            );
        }
    }

    @DisplayName("재고를 설정할 때, ")
    @Nested
    class Set {
        @DisplayName("0 이상의 수량을 주면, 그 값으로 그대로 설정된다.")
        @Test
        void setsStock_whenQuantityIsZeroOrPositive() {
            // arrange
            Stock stock = new Stock(5);

            // act
            stock.set(100);

            // assert
            assertThat(stock.remaining()).isEqualTo(100);
        }

        @DisplayName("음수 수량을 주면, BAD_REQUEST 예외가 발생하고 재고는 그대로 유지된다.")
        @Test
        void throwsBadRequestException_whenQuantityIsNegative() {
            // arrange
            Stock stock = new Stock(5);

            // act
            CoreException result = assertThrows(CoreException.class, () -> stock.set(-1));

            // assert
            assertAll(
                () -> assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                () -> assertThat(stock.remaining()).isEqualTo(5)
            );
        }
    }
}

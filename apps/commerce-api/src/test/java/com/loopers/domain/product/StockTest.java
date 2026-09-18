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
        @DisplayName("음수 수량이 주어지면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenQuantityIsNegative() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                new Stock(-1);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("재고 수량을 변경할 때, ")
    @Nested
    class Change {
        @DisplayName("0 이상인 수량이 주어지면, 증감이 아니라 해당 값으로 설정된다.")
        @Test
        void setsQuantityToGivenValue_whenQuantityIsNotNegative() {
            // arrange
            Stock stock = new Stock(10);

            // act
            stock.changeQuantity(0);

            // assert
            assertThat(stock.getQuantity()).isZero();
        }

        @DisplayName("음수 수량이 주어지면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenQuantityIsNegative() {
            // arrange
            Stock stock = new Stock(10);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                stock.changeQuantity(-1);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("음수 수량으로 실패하면, 기존 재고 수량이 유지된다.")
        @Test
        void keepsQuantity_whenChangeFails() {
            // arrange
            Stock stock = new Stock(10);

            // act
            assertThrows(CoreException.class, () -> {
                stock.changeQuantity(-1);
            });

            // assert
            assertThat(stock.getQuantity()).isEqualTo(10);
        }
    }

    @DisplayName("재고를 차감할 때, ")
    @Nested
    class Deduct {
        @DisplayName("재고가 충분하면, 요청한 수량만큼 차감된다.")
        @Test
        void deductsQuantity_whenStockIsSufficient() {
            // arrange
            Stock stock = new Stock(10);

            // act
            stock.deduct(3);

            // assert
            assertThat(stock.getQuantity()).isEqualTo(7);
        }

        @DisplayName("재고보다 많은 수량을 차감하면, CONFLICT 예외가 발생한다.")
        @Test
        void throwsConflictException_whenStockIsInsufficient() {
            // arrange
            Stock stock = new Stock(3);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                stock.deduct(4);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        }

        @DisplayName("재고가 부족해 실패하면, 기존 재고 수량이 유지된다.")
        @Test
        void keepsQuantity_whenDeductFails() {
            // arrange
            Stock stock = new Stock(3);

            // act
            assertThrows(CoreException.class, () -> {
                stock.deduct(4);
            });

            // assert
            assertThat(stock.getQuantity()).isEqualTo(3);
        }

        @DisplayName("재고와 같은 수량을 차감하면, 재고가 0이 된다.")
        @Test
        void deductsToZero_whenQuantityEqualsStock() {
            // arrange
            Stock stock = new Stock(5);

            // act
            stock.deduct(5);

            // assert
            assertThat(stock.getQuantity()).isZero();
        }

        @DisplayName("0인 수량을 차감하면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenQuantityIsZero() {
            // arrange
            Stock stock = new Stock(5);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                stock.deduct(0);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("음수 수량을 차감하면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenQuantityIsNegative() {
            // arrange
            Stock stock = new Stock(5);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                stock.deduct(-3);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("음수 수량으로 실패하면, 재고가 늘어나지 않고 기존 수량이 유지된다.")
        @Test
        void keepsQuantity_whenQuantityIsNegative() {
            // arrange
            Stock stock = new Stock(5);

            // act
            assertThrows(CoreException.class, () -> {
                stock.deduct(-3);
            });

            // assert
            assertThat(stock.getQuantity()).isEqualTo(5);
        }
    }
}

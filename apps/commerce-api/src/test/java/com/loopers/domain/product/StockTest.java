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

    /**
     * 재고 부족 판정과 차감은 ProductRepository.deductStockIfEnough 의 조건부 UPDATE 가 맡으므로, 입력 검증만 남긴다.
     */
    @DisplayName("차감 수량을 검증할 때, ")
    @Nested
    class ValidateDeductQuantity {
        @DisplayName("0인 수량이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenQuantityIsZero() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                Stock.validateDeductQuantity(0);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("음수 수량이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenQuantityIsNegative() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                Stock.validateDeductQuantity(-3);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }
}

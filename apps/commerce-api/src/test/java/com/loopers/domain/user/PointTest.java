package com.loopers.domain.user;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PointTest {

    @DisplayName("포인트를 충전할 때,")
    @Nested
    class Charge {
        @DisplayName("양수 금액이면, 잔액에 더해진다.")
        @Test
        void increasesBalance_whenAmountIsPositive() {
            // arrange
            Point point = new Point();

            // act
            point.charge(1000L);

            // assert
            assertThat(point.getBalance()).isEqualTo(1000L);
        }

        @DisplayName("0이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenAmountIsZero() {
            // arrange
            Point point = new Point();

            // act
            CoreException result = assertThrows(CoreException.class, () -> point.charge(0L));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("음수이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenAmountIsNegative() {
            // arrange
            Point point = new Point();

            // act
            CoreException result = assertThrows(CoreException.class, () -> point.charge(-1000L));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("포인트를 결제(차감)할 때,")
    @Nested
    class Pay {
        @DisplayName("잔액 이하의 양수 금액이면, 잔액에서 차감된다.")
        @Test
        void decreasesBalance_whenAmountIsWithinBalance() {
            // arrange
            Point point = new Point();
            point.charge(1000L);

            // act
            point.pay(700L);

            // assert
            assertThat(point.getBalance()).isEqualTo(300L);
        }

        @DisplayName("잔액과 정확히 같으면, 잔액이 0이 된다.")
        @Test
        void resultsInZeroBalance_whenAmountEqualsBalance() {
            // arrange
            Point point = new Point();
            point.charge(1000L);

            // act
            point.pay(1000L);

            // assert
            assertThat(point.getBalance()).isEqualTo(0L);
        }

        @DisplayName("0이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenAmountIsZero() {
            // arrange
            Point point = new Point();
            point.charge(1000L);

            // act
            CoreException result = assertThrows(CoreException.class, () -> point.pay(0L));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("음수이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenAmountIsNegative() {
            // arrange
            Point point = new Point();
            point.charge(1000L);

            // act
            CoreException result = assertThrows(CoreException.class, () -> point.pay(-100L));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("잔액을 초과하면, BAD_REQUEST 예외가 발생하고 잔액은 그대로 유지된다.")
        @Test
        void throwsBadRequestException_whenAmountExceedsBalance() {
            // arrange
            Point point = new Point();
            point.charge(1000L);

            // act
            CoreException result = assertThrows(CoreException.class, () -> point.pay(1001L));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            assertThat(point.getBalance()).isEqualTo(1000L);
        }
    }
}

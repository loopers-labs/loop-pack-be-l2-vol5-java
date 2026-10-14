package com.loopers.domain.point;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PointTest {
    @DisplayName("포인트를 충전할 때, ")
    @Nested
    class Charge {
        @DisplayName("양수 금액이 주어지면, 잔액이 그만큼 증가한다.")
        @Test
        void increasesBalance_whenAmountIsPositive() {
            // arrange
            Point point = new Point(1L);

            // act
            point.charge(1000L);

            // assert
            assertThat(point.getBalance()).isEqualTo(1000L);
        }

        @DisplayName("0 이하 금액이 주어지면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenAmountIsNotPositive() {
            // arrange
            Point point = new Point(1L);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                point.charge(0L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("충전 결과가 표현 범위를 초과하면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenResultOverflows() {
            // arrange
            Point point = new Point(1L);
            point.charge(Long.MAX_VALUE);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                point.charge(1L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("충전에 실패하면, 기존 잔액이 유지된다.")
        @Test
        void keepsBalance_whenChargeFails() {
            // arrange
            Point point = new Point(1L);
            point.charge(1000L);

            // act
            assertThrows(CoreException.class, () -> {
                point.charge(-1L);
            });

            // assert
            assertThat(point.getBalance()).isEqualTo(1000L);
        }
    }

    /**
     * 잔액 부족 판정과 차감은 PointRepository.deductIfEnough 의 조건부 UPDATE 가 맡으므로, 입력 검증만 남긴다.
     */
    @DisplayName("차감 금액을 검증할 때, ")
    @Nested
    class ValidateDeductAmount {
        @DisplayName("0 이하 금액이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenAmountIsNotPositive() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                Point.validateDeductAmount(0L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }
}

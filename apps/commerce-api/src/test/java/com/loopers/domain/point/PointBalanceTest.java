package com.loopers.domain.point;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PointBalanceTest {
    @DisplayName("포인트 잔액을 생성할 때, ")
    @Nested
    class Create {
        @DisplayName("잔액이 0이면, 정상적으로 생성된다.")
        @Test
        void createsPointBalance_whenBalanceIsZero() {
            // arrange & act
            PointBalance pointBalance = new PointBalance(0L);

            // assert
            assertThat(pointBalance.amount()).isZero();
        }

        @DisplayName("잔액이 음수이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenBalanceIsNegative() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                new PointBalance(-1L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("포인트를 충전할 때, ")
    @Nested
    class Charge {
        @DisplayName("양수 충전액이 주어지면, 기존 잔액에 더한 새 잔액을 반환한다.")
        @Test
        void addsChargeAmount_whenChargeAmountIsPositive() {
            // arrange
            PointBalance pointBalance = new PointBalance(100L);

            // act
            PointBalance chargedBalance = pointBalance.add(200L);

            // assert
            assertAll(
                () -> assertThat(chargedBalance.amount()).isEqualTo(300L),
                () -> assertThat(pointBalance.amount()).isEqualTo(100L)
            );
        }

        @DisplayName("충전액이 0이면, BAD_REQUEST 예외가 발생하고 기존 잔액을 유지한다.")
        @Test
        void throwsBadRequestException_whenChargeAmountIsZero() {
            // arrange
            PointBalance pointBalance = new PointBalance(100L);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                pointBalance.add(0L);
            });

            // assert
            assertAll(
                () -> assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                () -> assertThat(pointBalance.amount()).isEqualTo(100L)
            );
        }

        @DisplayName("충전액이 음수이면, BAD_REQUEST 예외가 발생하고 기존 잔액을 유지한다.")
        @Test
        void throwsBadRequestException_whenChargeAmountIsNegative() {
            // arrange
            PointBalance pointBalance = new PointBalance(100L);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                pointBalance.add(-1L);
            });

            // assert
            assertAll(
                () -> assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                () -> assertThat(pointBalance.amount()).isEqualTo(100L)
            );
        }

        @DisplayName("충전 후 잔액이 시스템이 저장할 수 있는 한도를 넘으면, BAD_REQUEST 예외가 발생하고 기존 잔액을 유지한다.")
        @Test
        void throwsBadRequestException_whenChargedBalanceExceedsMaximumValue() {
            // arrange
            PointBalance pointBalance = new PointBalance(Long.MAX_VALUE);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                pointBalance.add(1L);
            });

            // assert
            assertAll(
                () -> assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                () -> assertThat(pointBalance.amount()).isEqualTo(Long.MAX_VALUE)
            );
        }
    }
}

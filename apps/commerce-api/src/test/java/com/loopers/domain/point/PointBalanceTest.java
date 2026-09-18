package com.loopers.domain.point;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PointBalanceTest {
    @Test
    @DisplayName("양수 금액을 충전하면 잔액이 증가한다")
    void chargesPositiveAmount() {
        // arrange
        PointBalance point = PointBalance.empty(1L);

        // act
        point.charge(10_000L);

        // assert
        assertThat(point.getBalance()).isEqualTo(10_000L);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    @DisplayName("0 이하의 충전은 거절하고 기존 잔액을 유지한다")
    void rejectsNonPositiveChargeWithoutChangingBalance(long amount) {
        // arrange
        PointBalance point = PointBalance.empty(1L);
        point.charge(10_000L);

        // act
        CoreException error = assertThrows(CoreException.class, () -> point.charge(amount));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
        assertThat(point.getBalance()).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("충전 합산 범위를 넘으면 기존 잔액을 유지한다")
    void rejectsOverflowWithoutChangingBalance() {
        // arrange
        PointBalance point = PointBalance.empty(1L);
        point.charge(Long.MAX_VALUE);

        // act
        CoreException error = assertThrows(CoreException.class, () -> point.charge(1));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
        assertThat(point.getBalance()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("10,000원에서 7,000원을 차감하면 3,000원이 남는다")
    void deductsAmount() {
        // arrange
        PointBalance point = PointBalance.empty(1L);
        point.charge(10_000L);

        // act
        point.deduct(7_000L);

        // assert
        assertThat(point.getBalance()).isEqualTo(3_000L);
    }

    @Test
    @DisplayName("잔액 전부를 사용하면 0원이 된다")
    void allowsZeroRemainingBalance() {
        // arrange
        PointBalance point = PointBalance.empty(1L);
        point.charge(3_000L);

        // act
        point.deduct(3_000L);

        // assert
        assertThat(point.getBalance()).isZero();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    @DisplayName("0 이하의 차감액을 거절하고 잔액을 유지한다")
    void rejectsInvalidDeductionWithoutChangingBalance(long amount) {
        // arrange
        PointBalance point = PointBalance.empty(1L);
        point.charge(10_000L);

        // act
        CoreException error = assertThrows(CoreException.class, () -> point.deduct(amount));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
        assertThat(point.getBalance()).isEqualTo(10_000L);
    }
    @Test
    @DisplayName("기존 10,000원에 5,000원을 충전하면 15,000원이 된다")
    void addsChargeToExistingBalance() {
        // arrange
        PointBalance point = PointBalance.empty(1);
        point.charge(10_000);

        // act
        point.charge(5_000);

        // assert
        assertThat(point.getBalance()).isEqualTo(15_000);
    }
    @Test
    @DisplayName("잔액을 초과하는 차감은 잔액 부족으로 거절한다")
    void rejectsInsufficientBalance() {
        // arrange
        PointBalance point = PointBalance.empty(1);
        point.charge(10_000);

        // act
        CoreException error = assertThrows(CoreException.class, () -> point.deduct(10_001));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INSUFFICIENT_POINTS);
        assertThat(point.getBalance()).isEqualTo(10_000);
    }
}

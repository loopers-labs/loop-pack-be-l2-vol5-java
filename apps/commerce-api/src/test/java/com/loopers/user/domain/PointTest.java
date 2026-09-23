package com.loopers.user.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PointTest {

    @DisplayName("[INV-16] 포인트 잔액은 0 이상이다.")
    @Nested
    class NonNegativeBalance {

        @DisplayName("[경계값 분석] 잔액 0, 1인 포인트를 만들 수 있다.")
        @ParameterizedTest
        @ValueSource(longs = {0L, 1L})
        void createsPoint_whenBalanceIsZeroOrMore(long balance) {
            // act
            Point result = new Point(balance);

            // assert
            assertThat(result.balance()).isEqualTo(balance);
        }

        @DisplayName("[경계값 분석] 잔액 -1로 포인트를 만들면 포인트 잔액 오류로 거절한다.")
        @Test
        void throwsInvalidPointBalance_whenBalanceIsNegative() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> new Point(-1L));

            // assert
            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INVALID_POINT_BALANCE);
        }
    }

    @DisplayName("[INV-17] 충전액은 양의 정수다.")
    @Nested
    class PositiveChargeAmount {

        @DisplayName("[경계값 분석] 0과 -1을 충전하면 충전액 오류로 거절하고, 잔액은 그대로다.")
        @ParameterizedTest
        @ValueSource(longs = {0L, -1L})
        void throwsInvalidChargeAmount_whenAmountIsNotPositive(long amount) {
            // arrange
            Point point = new Point(1_000L);

            // act
            CoreException result = assertThrows(CoreException.class, () -> point.charge(amount));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INVALID_CHARGE_AMOUNT),
                () -> assertThat(point.balance()).isEqualTo(1_000L)
            );
        }
    }

    @DisplayName("[INV-18] 충전 뒤 잔액은 기존 잔액과 충전액의 합이다.")
    @Nested
    class ChargeAddsAmount {

        @DisplayName("[경계값 분석] 잔액 1000에 최소 충전액 1을 더하면 1001이 되고, 충전 전 잔액은 1000 그대로다.")
        @Test
        void returnsChargedPoint() {
            // arrange
            Point point = new Point(1_000L);

            // act
            Point result = point.charge(1L);

            // assert
            assertAll(
                () -> assertThat(result.balance()).isEqualTo(1_001L),
                () -> assertThat(point.balance()).isEqualTo(1_000L)
            );
        }
    }

    @DisplayName("[INV-19] 충전 뒤 잔액은 시스템이 표현할 수 있는 범위 안이다.")
    @Nested
    class ChargeWithinRange {

        @DisplayName("[경계값 분석] 최대 잔액에 1을 충전하면 한도 초과로 거절하고, 잔액은 그대로다.")
        @Test
        void throwsPointBalanceLimitExceeded_whenChargeOverflows() {
            // arrange
            Point point = new Point(Long.MAX_VALUE);

            // act
            CoreException result = assertThrows(CoreException.class, () -> point.charge(1L));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode())
                    .isEqualTo(ErrorCode.POINT_BALANCE_LIMIT_EXCEEDED),
                () -> assertThat(point.balance()).isEqualTo(Long.MAX_VALUE)
            );
        }
    }

    @DisplayName("[INV-20] 결제액은 현재 잔액 이하다.")
    @Nested
    class PayWithinBalance {

        @DisplayName("[동등 클래스 분할] 잔액 2000에서 1000을 결제하면 잔액이 1000이 된다.")
        @Test
        void paysAmountWithinBalance() {
            // arrange
            Point point = new Point(2_000L);

            // act
            Point result = point.pay(1_000L);

            // assert
            assertThat(result.balance()).isEqualTo(1_000L);
        }

        @DisplayName("[경계값 분석] 잔액과 같은 금액을 결제하면 잔액이 0이 된다.")
        @Test
        void pays_whenAmountEqualsBalance() {
            // arrange
            Point point = new Point(1_000L);

            // act
            Point result = point.pay(1_000L);

            // assert
            assertThat(result.balance()).isZero();
        }

        @DisplayName("[경계값 분석] 잔액보다 1 큰 금액을 결제하면 잔액 부족으로 거절하고, 잔액은 그대로다.")
        @Test
        void throwsInsufficientPoint_whenAmountExceedsBalance() {
            // arrange
            Point point = new Point(1_000L);

            // act
            CoreException result = assertThrows(CoreException.class, () -> point.pay(1_001L));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_POINT),
                () -> assertThat(point.balance()).isEqualTo(1_000L)
            );
        }
    }
}

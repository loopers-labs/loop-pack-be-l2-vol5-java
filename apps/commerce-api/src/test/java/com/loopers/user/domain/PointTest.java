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

    @DisplayName("[R-POINT-05] 포인트 잔액은 0일 수 있다.")
    @Nested
    class ZeroBalance {

        @DisplayName("[경계값 분석] 잔액 0인 포인트를 만들 수 있다.")
        @Test
        void createsPoint_whenBalanceIsZero() {
            Point point = new Point(0L);

            assertThat(point.balance()).isZero();
        }
    }

    @DisplayName("[R-POINT-04] 충전액은 양의 정수여야 한다.")
    @Nested
    class PositiveChargeAmount {

        @DisplayName("[경계값 분석] 0과 -1을 충전하면 충전액 오류로 거절한다.")
        @ParameterizedTest
        @ValueSource(longs = {0L, -1L})
        void throwsInvalidChargeAmount_whenAmountIsNotPositive(long amount) {
            Point point = new Point(1_000L);

            CoreException result = assertThrows(CoreException.class, () -> point.charge(amount));

            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INVALID_CHARGE_AMOUNT),
                () -> assertThat(point.balance()).isEqualTo(1_000L)
            );
        }
    }

    @DisplayName("[R-POINT-06] 충전하면 기존 잔액에 충전액을 더한다.")
    @Nested
    class Charge {

        @DisplayName("[경계값 분석] 잔액 1000에 최소 충전액 1을 더하면 1001이 된다.")
        @Test
        void returnsChargedPoint() {
            Point point = new Point(1_000L);

            Point result = point.charge(1L);

            assertAll(
                () -> assertThat(result.balance()).isEqualTo(1_001L),
                () -> assertThat(point.balance()).isEqualTo(1_000L)
            );
        }
    }

    @DisplayName("[R-POINT-07] 충전 후 잔액은 표현 범위를 넘을 수 없다.")
    @Nested
    class RejectOverflow {

        @DisplayName("[경계값 분석] 최대 잔액에 1을 충전하면 한도 초과로 거절한다.")
        @Test
        void throwsPointBalanceLimitExceeded_whenChargeOverflows() {
            Point point = new Point(Long.MAX_VALUE);

            CoreException result = assertThrows(CoreException.class, () -> point.charge(1L));

            assertAll(
                () -> assertThat(result.getErrorCode())
                    .isEqualTo(ErrorCode.POINT_BALANCE_LIMIT_EXCEEDED),
                () -> assertThat(point.balance()).isEqualTo(Long.MAX_VALUE)
            );
        }
    }

    @DisplayName("[R-POINT-03] 1포인트의 가치는 1원이다.")
    @Nested
    class OnePointPerWon {

        @DisplayName("[동등 클래스 분할] 1000원을 결제하면 포인트 1000이 차감된다.")
        @Test
        void paysSameNumberOfPointsAsWon() {
            Point point = new Point(2_000L);

            Point result = point.pay(1_000L);

            assertThat(result.balance()).isEqualTo(1_000L);
        }
    }

    @DisplayName("[R-ORDER-09] 주문 금액 이하의 포인트 잔액이 있어야 결제할 수 있다.")
    @Nested
    class PayWithinBalance {

        @DisplayName("[경계값 분석] 잔액과 같은 금액을 결제하면 잔액이 0이 된다.")
        @Test
        void pays_whenAmountEqualsBalance() {
            Point point = new Point(1_000L);

            Point result = point.pay(1_000L);

            assertThat(result.balance()).isZero();
        }

        @DisplayName("[경계값 분석] 잔액보다 1 큰 금액을 결제하면 거절하고 잔액을 유지한다.")
        @Test
        void throwsInsufficientPoint_whenAmountExceedsBalance() {
            Point point = new Point(1_000L);

            CoreException result = assertThrows(CoreException.class, () -> point.pay(1_001L));

            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_POINT),
                () -> assertThat(point.balance()).isEqualTo(1_000L)
            );
        }
    }
}

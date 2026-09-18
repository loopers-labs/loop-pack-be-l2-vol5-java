package com.loopers.domain.point;

import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PointTest {

    private static Point pointWithBalance(long balance) {
        Point point = new Point(1L);
        if (balance > 0) {
            point.charge(balance);
        }
        return point;
    }

    @DisplayName("충전한 적 없는 Point 의 잔액은 0 원이다.")
    @Test
    void startsWithZeroBalance() {
        assertThat(new Point(1L).getBalance()).isZero();
    }

    @DisplayName("충전할 때, ")
    @Nested
    class Charge {
        @DisplayName("양수면, 잔액에 더한다.")
        @Test
        void addsAmount() {
            // arrange
            Point point = pointWithBalance(1_000L);

            // act
            point.charge(500L);

            // assert
            assertThat(point.getBalance()).isEqualTo(1_500L);
        }

        @DisplayName("0 이하면, INVALID_CHARGE_AMOUNT 예외가 발생하고 잔액은 그대로다. (PNT-01)")
        @ParameterizedTest
        @ValueSource(longs = {0L, -1L})
        void throwsInvalidChargeAmount_andKeepsBalance_whenAmountIsNotPositive(long amount) {
            // arrange
            Point point = pointWithBalance(1_000L);

            // act
            CoreException result = assertThrows(CoreException.class, () -> point.charge(amount));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(PointErrorCode.INVALID_CHARGE_AMOUNT),
                () -> assertThat(point.getBalance()).isEqualTo(1_000L)
            );
        }

        @DisplayName("합계가 저장 타입의 범위를 넘으면, BALANCE_LIMIT_EXCEEDED 예외가 발생하고 잔액은 그대로다. (PNT-02)")
        @Test
        void throwsBalanceLimitExceeded_andKeepsBalance_whenSumOverflows() {
            // arrange
            Point point = pointWithBalance(Long.MAX_VALUE);

            // act
            CoreException result = assertThrows(CoreException.class, () -> point.charge(1L));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(PointErrorCode.BALANCE_LIMIT_EXCEEDED),
                () -> assertThat(point.getBalance()).isEqualTo(Long.MAX_VALUE)
            );
        }
    }

    @DisplayName("결제할 수 있는지 판단할 때, ")
    @Nested
    class CanPay {
        @DisplayName("잔액 이하면 참이고, 잔액보다 크거나 음수면 거짓이다.")
        @Test
        void comparesWithBalance() {
            Point point = pointWithBalance(1_000L);

            assertAll(
                () -> assertThat(point.canPay(1_000L)).isTrue(),
                () -> assertThat(point.canPay(1_001L)).isFalse(),
                () -> assertThat(point.canPay(-1L)).isFalse()
            );
        }

        @DisplayName("0 원 결제는 잔액이 0 이어도 참이다. (D-30)")
        @Test
        void allowsZeroPayment() {
            assertThat(new Point(1L).canPay(0L)).isTrue();
        }
    }

    @DisplayName("결제할 때, ")
    @Nested
    class Pay {
        @DisplayName("잔액 이하면, 그만큼 뺀다. 10,000 원에서 7,000 원을 내면 3,000 원이 남는다.")
        @Test
        void subtractsAmount() {
            Point point = pointWithBalance(10_000L);

            point.pay(7_000L);

            assertThat(point.getBalance()).isEqualTo(3_000L);
        }

        @DisplayName("잔액보다 크면, INSUFFICIENT_POINT 예외가 발생하고 잔액은 그대로다.")
        @Test
        void throwsInsufficientPoint_andKeepsBalance_whenAmountExceedsBalance() {
            // arrange
            Point point = pointWithBalance(1_000L);

            // act
            CoreException result = assertThrows(CoreException.class, () -> point.pay(1_001L));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(PointErrorCode.INSUFFICIENT_POINT),
                () -> assertThat(point.getBalance()).isEqualTo(1_000L)
            );
        }
    }
}

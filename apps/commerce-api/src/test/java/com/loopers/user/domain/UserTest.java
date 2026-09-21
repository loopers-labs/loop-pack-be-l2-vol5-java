package com.loopers.user.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserTest {

    @DisplayName("[INV-22] 새 고객의 잔액은 0이다.")
    @Nested
    class InitialBalance {

        @DisplayName("[경계값 분석] 한 번도 충전하지 않은 고객의 잔액은 0이다.")
        @Test
        void startsWithZeroPoint() {
            // act
            User user = new User();

            // assert
            assertPointBalance(user, 0L);
        }
    }

    @DisplayName("[INV-21] 거절된 충전은 잔액을 바꾸지 않는다.")
    @Nested
    class KeepBalanceOnRejectedCharge {

        @DisplayName("[경계값 분석] 0을 충전하면 충전액 오류로 거절하고, 잔액은 그대로다.")
        @Test
        void throwsInvalidChargeAmount_andKeepsBalance() {
            // arrange
            User user = new User();
            user.charge(1_000L);

            // act
            CoreException result = assertThrows(CoreException.class, () -> user.charge(0L));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INVALID_CHARGE_AMOUNT),
                () -> assertPointBalance(user, 1_000L)
            );
        }

        @DisplayName("[경계값 분석] 최대 잔액에 1을 충전하면 한도 초과로 거절하고, 잔액은 그대로다.")
        @Test
        void throwsPointBalanceLimitExceeded_andKeepsBalance() {
            // arrange
            User user = new User();
            user.charge(Long.MAX_VALUE);

            // act
            CoreException result = assertThrows(CoreException.class, () -> user.charge(1L));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode())
                    .isEqualTo(ErrorCode.POINT_BALANCE_LIMIT_EXCEEDED),
                () -> assertPointBalance(user, Long.MAX_VALUE)
            );
        }
    }

    @DisplayName("[INV-20] 결제액은 현재 잔액 이하다.")
    @Nested
    class PayWithinBalance {

        @DisplayName("[경계값 분석] 잔액과 같은 금액을 결제하면 잔액이 0이 된다.")
        @Test
        void pays_whenAmountEqualsBalance() {
            // arrange
            User user = new User();
            user.charge(1_000L);

            // act
            user.pay(1_000L);

            // assert
            assertPointBalance(user, 0L);
        }

        @DisplayName("[경계값 분석] 잔액보다 1 큰 금액을 결제하면 잔액 부족으로 거절하고, 잔액은 그대로다.")
        @Test
        void throwsInsufficientPoint_andKeepsBalance() {
            // arrange
            User user = new User();
            user.charge(1_000L);

            // act
            CoreException result = assertThrows(CoreException.class, () -> user.pay(1_001L));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_POINT),
                () -> assertPointBalance(user, 1_000L)
            );
        }
    }

    private static void assertPointBalance(User user, long expectedBalance) {
        assertThat(user.getPoint())
            .isNotNull()
            .extracting(Point::balance)
            .isEqualTo(expectedBalance);
    }
}

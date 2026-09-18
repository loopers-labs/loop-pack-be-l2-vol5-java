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

    @DisplayName("[P-POINT-01] 한 번도 충전하지 않은 고객의 잔액은 0이다.")
    @Nested
    class InitialPoint {

        @DisplayName("[경계값 분석] 새 고객의 포인트 잔액은 0이다.")
        @Test
        void startsWithZeroPoint() {
            User user = new User();

            assertPointBalance(user, 0L);
        }
    }

    @DisplayName("[R-POINT-08] 유효하지 않은 충전 요청은 거절하고 기존 잔액을 유지한다.")
    @Nested
    class RejectInvalidCharge {

        @DisplayName("[경계값 분석] 0을 충전하면 거절하고 기존 잔액을 유지한다.")
        @Test
        void throwsInvalidChargeAmount_andKeepsPoint() {
            User user = new User();
            user.charge(1_000L);

            CoreException result = assertThrows(CoreException.class, () -> user.charge(0L));

            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INVALID_CHARGE_AMOUNT),
                () -> assertPointBalance(user, 1_000L)
            );
        }
    }

    @DisplayName("[R-ORDER-10] 포인트가 부족하면 결제를 거절하고 잔액을 유지한다.")
    @Nested
    class RejectPaymentWithInsufficientPoint {

        @DisplayName("[경계값 분석] 잔액보다 1 큰 금액을 결제하면 거절하고 잔액을 유지한다.")
        @Test
        void throwsInsufficientPoint_andKeepsPoint() {
            User user = new User();
            user.charge(1_000L);

            CoreException result = assertThrows(CoreException.class, () -> user.pay(1_001L));

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

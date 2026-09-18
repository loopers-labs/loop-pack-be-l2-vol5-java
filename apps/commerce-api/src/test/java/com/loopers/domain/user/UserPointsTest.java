package com.loopers.domain.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

class UserPointsTest {

    @DisplayName("POINT-01: 명시한 사용자 ID와 잔액 0으로 시작한다.")
    @Test
    void startsWithZeroBalance() {
        User user = new User(1L);

        assertAll(
            () -> assertThat(user.getId()).isEqualTo(1L),
            () -> assertThat(user.getBalance()).isZero()
        );
    }

    @DisplayName("POINT-02: 같은 충전액으로 다시 충전하면 매번 잔액이 증가한다.")
    @Test
    void appliesEveryCharge() {
        User user = new User(1L);
        user.charge(2000L);

        user.charge(3000L);
        assertThat(user.getBalance()).isEqualTo(5000L);
        user.charge(3000L);
        assertThat(user.getBalance()).isEqualTo(8000L);
    }

    @DisplayName("POINT-03: 0·음수 충전은 기존 잔액을 보존한다.")
    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void rejectsNonPositiveCharge(long amount) {
        User user = new User(1L);
        user.charge(2000L);

        assertAll(
            () -> assertThatThrownBy(() -> user.charge(amount))
                .isInstanceOfSatisfying(PointsException.class,
                    error -> assertThat(error.getReason()).isEqualTo(PointsException.Reason.INVALID_AMOUNT)),
            () -> assertThat(user.getBalance()).isEqualTo(2000L)
        );
    }

    @DisplayName("POINT-04: Long 최댓값 잔액은 허용하고 1만 초과해도 기존 잔액을 보존한다.")
    @Test
    void allowsMaximumBalanceAndRejectsOverflow() {
        User user = new User(1L);
        user.charge(Long.MAX_VALUE);

        assertAll(
            () -> assertThatThrownBy(() -> user.charge(1L))
                .isInstanceOfSatisfying(PointsException.class,
                    error -> assertThat(error.getReason()).isEqualTo(PointsException.Reason.BALANCE_LIMIT_EXCEEDED)),
            () -> assertThat(user.getBalance()).isEqualTo(Long.MAX_VALUE)
        );
    }

    @DisplayName("POINT-05: 유효한 최댓값 충전액도 기존 잔액과 합산해 초과하면 거절한다.")
    @Test
    void rejectsOverflowWithValidMaximumCharge() {
        User user = new User(1L);
        user.charge(1L);

        assertAll(
            () -> assertThatThrownBy(() -> user.charge(Long.MAX_VALUE))
                .isInstanceOfSatisfying(PointsException.class,
                    error -> assertThat(error.getReason()).isEqualTo(PointsException.Reason.BALANCE_LIMIT_EXCEEDED)),
            () -> assertThat(user.getBalance()).isEqualTo(1L)
        );
    }

    @DisplayName("POINT-06: 잔액 이하를 차감하고 전액 차감하면 0이 된다.")
    @Test
    void deductsUpToAvailableBalance() {
        User user = new User(1L);
        user.charge(Long.MAX_VALUE);

        user.deduct(Long.MAX_VALUE - 1L);
        assertThat(user.getBalance()).isEqualTo(1L);
        user.deduct(1L);
        assertThat(user.getBalance()).isZero();
    }

    @DisplayName("POINT-09: 차감 사전 검증은 성공·실패 모두 잔액을 변경하지 않는다.")
    @Test
    void validatesDeductionWithoutChangingBalance() {
        User user = new User(1L);
        user.charge(3999L);

        user.validateDeduction(3999L);
        assertThatThrownBy(() -> user.validateDeduction(4000L))
            .isInstanceOfSatisfying(PointsException.class,
                error -> assertThat(error.getReason()).isEqualTo(PointsException.Reason.INSUFFICIENT_POINTS));
        assertThat(user.getBalance()).isEqualTo(3999L);
    }

    @DisplayName("POINT-07: 잔액 부족이면 차감하지 않는다.")
    @Test
    void preservesBalanceWhenDeductionExceedsBalance() {
        User user = new User(1L);
        user.charge(3999L);

        assertAll(
            () -> assertThatThrownBy(() -> user.deduct(4000L))
                .isInstanceOfSatisfying(PointsException.class,
                    error -> assertThat(error.getReason()).isEqualTo(PointsException.Reason.INSUFFICIENT_POINTS)),
            () -> assertThat(user.getBalance()).isEqualTo(3999L)
        );
    }

    @DisplayName("POINT-08: 0·음수 차감을 거절하고 잔액을 보존한다.")
    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void rejectsNonPositiveDeduction(long amount) {
        User user = new User(1L);
        user.charge(2000L);

        assertAll(
            () -> assertThatThrownBy(() -> user.deduct(amount))
                .isInstanceOfSatisfying(PointsException.class,
                    error -> assertThat(error.getReason()).isEqualTo(PointsException.Reason.INVALID_AMOUNT)),
            () -> assertThat(user.getBalance()).isEqualTo(2000L)
        );
    }
}

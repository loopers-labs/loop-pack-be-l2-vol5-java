package com.loopers.domain.point;

import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.loopers.support.ErrorAssertions.assertThrowsErrorType;
import static org.assertj.core.api.Assertions.assertThat;

/** AG-05 포인트. INV-01, INV-02. */
class PointModelTest {

    @DisplayName("[INV-01] 잔액 0 인 계정을 만들 수 있다 (0 허용).")
    @Test
    void create_allowsZeroBalance() {
        PointModel point = new PointModel(1L, 0L);

        assertThat(point.getBalance()).isZero();
    }

    @DisplayName("[INV-01] 음수 잔액으로는 만들 수 없다.")
    @Test
    void create_rejectsNegativeBalance() {
        assertThrowsErrorType(() -> new PointModel(1L, -1L), ErrorType.INVALID_AMOUNT);
    }

    @DisplayName("[FR-POINT-01] 충전하면 잔액 = 기존 + amount.")
    @Test
    void charge_addsAmount() {
        PointModel point = new PointModel(1L, 100L);

        point.charge(50L);

        assertThat(point.getBalance()).isEqualTo(150L);
    }

    @DisplayName("[ER-09 INVALID_AMOUNT] 충전 amount 누락·0·음수는 거부, 잔액 유지.")
    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L})
    void charge_throwsInvalidAmount(Long amount) {
        PointModel point = new PointModel(1L, 100L);

        assertThrowsErrorType(() -> point.charge(amount), ErrorType.INVALID_AMOUNT);

        assertThat(point.getBalance()).isEqualTo(100L);
    }

    @DisplayName("[INV-02][ER-10 BALANCE_LIMIT_EXCEEDED] 잔액 + amount 가 표현 범위를 넘으면 거부, 잔액 유지 (ASM-05).")
    @Test
    void charge_throwsBalanceLimitExceeded_onOverflow() {
        PointModel point = new PointModel(1L, Long.MAX_VALUE - 1);

        assertThrowsErrorType(() -> point.charge(2L), ErrorType.BALANCE_LIMIT_EXCEEDED);

        assertThat(point.getBalance()).isEqualTo(Long.MAX_VALUE - 1);
    }

    @DisplayName("[INV-02] 잔액 + amount 가 정확히 표현 범위 상한이면 허용된다.")
    @Test
    void charge_allowsExactMax() {
        PointModel point = new PointModel(1L, Long.MAX_VALUE - 1);

        point.charge(1L);

        assertThat(point.getBalance()).isEqualTo(Long.MAX_VALUE);
    }

    @DisplayName("[FR-POINT-03] 차감하면 잔액 = 기존 − amount. 잔액 전부를 차감해 0 으로 만들 수 있다 (INV-01).")
    @Test
    void deduct_subtractsAmount_downToZero() {
        PointModel point = new PointModel(1L, 100L);

        point.deduct(100L);

        assertThat(point.getBalance()).isZero();
    }

    @DisplayName("[INV-01][ER-11 INSUFFICIENT_POINT] 잔액 < amount 면 거부, 잔액 유지.")
    @Test
    void deduct_throwsInsufficientPoint() {
        PointModel point = new PointModel(1L, 100L);

        assertThrowsErrorType(() -> point.deduct(101L), ErrorType.INSUFFICIENT_POINT);

        assertThat(point.getBalance()).isEqualTo(100L);
    }

    @DisplayName("[ER-09 INVALID_AMOUNT] 차감 amount 누락·0·음수는 거부, 잔액 유지.")
    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -5L})
    void deduct_throwsInvalidAmount(Long amount) {
        PointModel point = new PointModel(1L, 100L);

        assertThrowsErrorType(() -> point.deduct(amount), ErrorType.INVALID_AMOUNT);

        assertThat(point.getBalance()).isEqualTo(100L);
    }
}

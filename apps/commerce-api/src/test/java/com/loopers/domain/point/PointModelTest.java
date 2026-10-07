package com.loopers.domain.point;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PointModelTest {

    @DisplayName("포인트를 생성하면, 잔액은 0원이다.")
    @Test
    void startsWithZeroBalance() {
        // arrange & act
        PointModel point = new PointModel(1L);

        // assert
        assertThat(point.getBalance()).isEqualTo(0L);
    }

    @DisplayName("잔액 0원에서 10,000을 충전하고 7,000을 사용하면, 잔액은 3,000이다.")
    @Test
    void chargesAndUses_reflectingBalance() {
        // arrange
        PointModel point = new PointModel(1L);

        // act
        point.charge(10_000L);
        point.use(7_000L);

        // assert
        assertThat(point.getBalance()).isEqualTo(3_000L);
    }

    @DisplayName("충전액이 0 이하이거나 누락이면, 거절하고 잔액을 유지한다.")
    @Test
    void rejectsCharge_whenAmountIsNotPositive() {
        // arrange
        PointModel point = new PointModel(1L);
        point.charge(1_000L);

        // act & assert
        assertThatThrownBy(() -> point.charge(0L))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
        assertThatThrownBy(() -> point.charge(-100L))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
        assertThatThrownBy(() -> point.charge(null))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
        assertThat(point.getBalance()).isEqualTo(1_000L);
    }

    @DisplayName("1회 충전 한도(1,000,000)를 초과하면, 거절하고 잔액을 유지한다.")
    @Test
    void rejectsCharge_whenAmountExceedsChargeLimit() {
        // arrange
        PointModel point = new PointModel(1L);

        // act & assert
        assertThatThrownBy(() -> point.charge(1_000_001L))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
        assertThat(point.getBalance()).isEqualTo(0L);
    }

    @DisplayName("잔액 9,500,000에서 600,000 충전을 요청하면, 잔액 한도 초과로 거절하고 잔액을 유지한다.")
    @Test
    void rejectsCharge_whenBalanceWouldExceedLimit() {
        // arrange
        PointModel point = new PointModel(1L);
        for (int i = 0; i < 9; i++) {
            point.charge(1_000_000L);
        }
        point.charge(500_000L);

        // act & assert
        assertThatThrownBy(() -> point.charge(600_000L))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
        assertThat(point.getBalance()).isEqualTo(9_500_000L);
    }

    @DisplayName("잔액보다 큰 사용을 요청하면, 거절하고 잔액을 유지한다.")
    @Test
    void rejectsUse_whenAmountExceedsBalance() {
        // arrange
        PointModel point = new PointModel(1L);
        point.charge(1_000L);

        // act & assert
        assertThatThrownBy(() -> point.use(2_000L))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
        assertThat(point.getBalance()).isEqualTo(1_000L);
    }
}

package com.loopers.domain.point;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PointBalanceTest {
    @Test
    void 양수_금액을_충전하면_잔액이_증가한다() {
        // arrange
        PointBalance point = PointBalance.empty(1L);

        // act
        point.charge(10_000L);

        // assert
        assertThat(point.getBalance()).isEqualTo(10_000L);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void 충전액이_0_이하면_거절하고_기존_잔액을_유지한다(long amount) {
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
    void 충전_합산_범위를_넘으면_기존_잔액을_유지한다() {
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
    void 잔액_10000원에서_7000원을_차감하면_3000원이_남는다() {
        // arrange
        PointBalance point = PointBalance.empty(1L);
        point.charge(10_000L);

        // act
        point.deduct(7_000L);

        // assert
        assertThat(point.getBalance()).isEqualTo(3_000L);
    }

    @Test
    void 잔액_전부를_사용하면_0원이_된다() {
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
    void 차감액이_0_이하면_거절하고_잔액을_유지한다(long amount) {
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
    void 기존_잔액_10000원에_5000원을_충전하면_15000원이_된다() {
        // arrange
        PointBalance point = PointBalance.empty(1);
        point.charge(10_000);

        // act
        point.charge(5_000);

        // assert
        assertThat(point.getBalance()).isEqualTo(15_000);
    }

    @Test
    void 잔액을_초과하는_차감은_잔액_부족으로_거절한다() {
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

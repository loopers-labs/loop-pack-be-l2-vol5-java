package com.loopers.domain.point;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("Point 는 사용자의 현재 잔액과 충전·사용 규칙을 책임진다.")
class PointModelTest {

    private static final long USER_ID = 1L;

    @DisplayName("생성")
    @Nested
    class Create {
        @DisplayName("새로 만든 Point 의 잔액은 0원이다.")
        @Test
        void startsWithZeroBalance() {
            PointModel point = PointModel.of(USER_ID);

            assertAll(
                () -> assertThat(point.getUserId()).isEqualTo(USER_ID),
                () -> assertThat(point.getBalance()).isZero()
            );
        }
    }

    @DisplayName("충전")
    @Nested
    class Charge {
        @DisplayName("충전액을 잔액에 더하고 변경 전후 값을 담은 PointChange 를 반환한다.")
        @Test
        void increasesBalance() {
            PointModel point = PointModel.of(USER_ID);

            PointChange change = point.charge(10_000L);

            assertAll(
                () -> assertThat(point.getBalance()).isEqualTo(10_000L),
                () -> assertThat(change.beforeBalance()).isZero(),
                () -> assertThat(change.afterBalance()).isEqualTo(10_000L),
                () -> assertThat(change.changedAmount()).isEqualTo(10_000L)
            );
        }

        @DisplayName("충전액 0 은 거절하고 잔액을 유지한다.")
        @Test
        void rejectsZeroAmount() {
            PointModel point = PointModel.of(USER_ID);
            point.charge(1_000L);

            assertThatThrownBy(() -> point.charge(0L))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INVALID_POINT_AMOUNT);
            assertThat(point.getBalance()).isEqualTo(1_000L);
        }

        @DisplayName("음수 충전액은 거절하고 잔액을 유지한다.")
        @Test
        void rejectsNegativeAmount() {
            PointModel point = PointModel.of(USER_ID);
            point.charge(1_000L);

            assertThatThrownBy(() -> point.charge(-1L))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INVALID_POINT_AMOUNT);
            assertThat(point.getBalance()).isEqualTo(1_000L);
        }

        @DisplayName("합산한 잔액이 표현 범위를 넘으면 거절하고 잔액을 유지한다.")
        @Test
        void rejectsOverflow() {
            PointModel point = PointModel.of(USER_ID);
            point.charge(Long.MAX_VALUE);

            assertThatThrownBy(() -> point.charge(1L))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.NUMERIC_OVERFLOW);
            assertThat(point.getBalance()).isEqualTo(Long.MAX_VALUE);
        }
    }

    @DisplayName("사용")
    @Nested
    class Use {
        @DisplayName("사용액을 잔액에서 빼고 변경 전후 값을 담은 PointChange 를 반환한다.")
        @Test
        void decreasesBalance() {
            PointModel point = PointModel.of(USER_ID);
            point.charge(10_000L);

            PointChange change = point.use(7_000L);

            assertAll(
                () -> assertThat(point.getBalance()).isEqualTo(3_000L),
                () -> assertThat(change.beforeBalance()).isEqualTo(10_000L),
                () -> assertThat(change.afterBalance()).isEqualTo(3_000L),
                () -> assertThat(change.changedAmount()).isEqualTo(7_000L)
            );
        }

        @DisplayName("잔액 전액을 사용하면 잔액 0 을 허용한다.")
        @Test
        void allowsZeroBalanceAfterUse() {
            PointModel point = PointModel.of(USER_ID);
            point.charge(7_000L);

            point.use(7_000L);

            assertThat(point.getBalance()).isZero();
        }

        @DisplayName("잔액보다 큰 금액은 INSUFFICIENT_POINT 로 거절하고 잔액을 유지한다.")
        @Test
        void rejectsInsufficientBalance() {
            PointModel point = PointModel.of(USER_ID);
            point.charge(6_999L);

            assertThatThrownBy(() -> point.use(7_000L))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INSUFFICIENT_POINT);
            assertThat(point.getBalance()).isEqualTo(6_999L);
        }

        @DisplayName("0 이하의 사용액은 거절하고 잔액을 유지한다.")
        @Test
        void rejectsNonPositiveAmount() {
            PointModel point = PointModel.of(USER_ID);
            point.charge(1_000L);

            assertThatThrownBy(() -> point.use(0L))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INVALID_POINT_AMOUNT);
            assertThat(point.getBalance()).isEqualTo(1_000L);
        }
    }
}

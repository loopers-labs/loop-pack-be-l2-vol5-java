package com.loopers.domain.point;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("PointHistory 는 포인트 변경 결과와 원인을 기록한다.")
class PointHistoryModelTest {

    private static final long POINT_ID = 10L;
    private static final long ORDER_ID = 77L;

    @DisplayName("충전 이력")
    @Nested
    class Charged {
        @DisplayName("변경 전후 잔액과 충전 원인을 기록하고 주문 참조는 남기지 않는다.")
        @Test
        void recordsChargeWithoutOrderReference() {
            PointChange change = new PointChange(0L, 10_000L, 10_000L);

            PointHistoryModel history = PointHistoryModel.charged(POINT_ID, change);

            assertAll(
                () -> assertThat(history.getPointId()).isEqualTo(POINT_ID),
                () -> assertThat(history.getBeforeBalance()).isZero(),
                () -> assertThat(history.getAfterBalance()).isEqualTo(10_000L),
                () -> assertThat(history.getChangedAmount()).isEqualTo(10_000L),
                () -> assertThat(history.getCause()).isEqualTo(PointChangeCause.CHARGE),
                () -> assertThat(history.getOrderId()).isNull()
            );
        }
    }

    @DisplayName("주문 사용 이력")
    @Nested
    class UsedForOrder {
        @DisplayName("변경 전후 잔액과 주문 사용 원인, 원인이 된 주문 식별자를 기록한다.")
        @Test
        void recordsOrderUsageWithOrderReference() {
            PointChange change = new PointChange(10_000L, 3_000L, 7_000L);

            PointHistoryModel history = PointHistoryModel.usedForOrder(POINT_ID, ORDER_ID, change);

            assertAll(
                () -> assertThat(history.getPointId()).isEqualTo(POINT_ID),
                () -> assertThat(history.getBeforeBalance()).isEqualTo(10_000L),
                () -> assertThat(history.getAfterBalance()).isEqualTo(3_000L),
                () -> assertThat(history.getChangedAmount()).isEqualTo(7_000L),
                () -> assertThat(history.getCause()).isEqualTo(PointChangeCause.ORDER_USE),
                () -> assertThat(history.getOrderId()).isEqualTo(ORDER_ID)
            );
        }
    }
}

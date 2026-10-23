package com.loopers.product.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockReservationTest {

    private static StockReservation reserved() {
        return new StockReservation(10L, 1L, 2);
    }

    @DisplayName("재고를 선점할 때, ")
    @Nested
    class Create {

        @DisplayName("ADR-W5-02 주문 10이 상품 1을 2개 선점하면 RESERVED다.")
        @Test
        void startsReserved() {
            // act
            StockReservation reservation = new StockReservation(10L, 1L, 2);

            // assert
            assertThat(reservation.getOrderId()).isEqualTo(10L);
            assertThat(reservation.getProductId()).isEqualTo(1L);
            assertThat(reservation.getQuantity()).isEqualTo(2);
            assertThat(reservation.getStatus()).isEqualTo(StockReservationStatus.RESERVED);
        }

        @DisplayName("ADR-W5-02 0개 이하 선점은 거절한다.")
        @Test
        void rejectsNonPositiveQuantity() {
            // act & assert
            assertThatThrownBy(() -> new StockReservation(10L, 1L, 0))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
            assertThatThrownBy(() -> new StockReservation(10L, 1L, -1))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("결제 성공으로 소비할 때, ")
    @Nested
    class Consume {

        @DisplayName("R-1 RESERVED는 CONSUMED가 되고 바뀌었다고 답한다. 두 번째 소비는 바뀌지 않았다고 답한다.")
        @Test
        void consumesOnce() {
            // arrange
            StockReservation reservation = reserved();

            // act
            boolean first = reservation.consume();
            boolean second = reservation.consume();

            // assert
            assertThat(first).isTrue();
            assertThat(second).isFalse();
            assertThat(reservation.getStatus()).isEqualTo(StockReservationStatus.CONSUMED);
        }

        @DisplayName("R-4 RELEASED는 소비하지 않고 RELEASED 그대로다.")
        @Test
        void doesNotConsumeReleased() {
            // arrange
            StockReservation reservation = reserved();
            reservation.release();

            // act
            boolean changed = reservation.consume();

            // assert
            assertThat(changed).isFalse();
            assertThat(reservation.getStatus()).isEqualTo(StockReservationStatus.RELEASED);
        }
    }

    @DisplayName("확인된 결제 실패로 반환할 때, ")
    @Nested
    class Release {

        @DisplayName("R-2 RESERVED는 RELEASED가 되고 바뀌었다고 답한다. 두 번째 반환은 바뀌지 않았다고 답한다 (재고 5→3→5, 7이 되면 안 된다).")
        @Test
        void releasesOnce() {
            // arrange
            StockReservation reservation = reserved();

            // act
            boolean first = reservation.release();
            boolean second = reservation.release();

            // assert
            assertThat(first).isTrue();
            assertThat(second).isFalse();
            assertThat(reservation.getStatus()).isEqualTo(StockReservationStatus.RELEASED);
        }

        @DisplayName("R-4 CONSUMED는 반환하지 않고 CONSUMED 그대로다.")
        @Test
        void doesNotReleaseConsumed() {
            // arrange
            StockReservation reservation = reserved();
            reservation.consume();

            // act
            boolean changed = reservation.release();

            // assert
            assertThat(changed).isFalse();
            assertThat(reservation.getStatus()).isEqualTo(StockReservationStatus.CONSUMED);
        }
    }
}

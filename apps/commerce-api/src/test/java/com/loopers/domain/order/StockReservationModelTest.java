package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StockReservationModelTest {

    @DisplayName("재고 예약을 만들면, 주문에 묶인 RESERVED 상태다.")
    @Test
    void startsReserved() {
        // act
        StockReservationModel reservation = new StockReservationModel(42L);

        // assert
        assertThat(reservation.getOrderId()).isEqualTo(42L);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RESERVED);
    }

    @DisplayName("주문이 없으면, BAD_REQUEST 예외가 발생한다.")
    @Test
    void throwsBadRequestException_whenOrderIsMissing() {
        // act
        CoreException result = assertThrows(CoreException.class, () -> new StockReservationModel(null));

        // assert
        assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
    }

    @DisplayName("반환은 한 번만 일어난다. 처음 반환할 때만 true이고, 같은 실패 알림이 다시 와도 false다.")
    @Test
    void releasesOnlyOnce() {
        // arrange
        StockReservationModel reservation = new StockReservationModel(42L);

        // act
        boolean first = reservation.release();
        boolean second = reservation.release();

        // assert
        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    }

    @DisplayName("소비는 한 번만 일어나고, 소비한 예약은 반환할 수 없다.")
    @Test
    void consumesOnly_andCannotBeReleasedAfterwards() {
        // arrange
        StockReservationModel reservation = new StockReservationModel(42L);

        // act
        boolean consumed = reservation.consume();
        boolean consumedAgain = reservation.consume();
        boolean released = reservation.release();

        // assert
        assertThat(consumed).isTrue();
        assertThat(consumedAgain).isFalse();
        assertThat(released).isFalse();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONSUMED);
    }

    @DisplayName("반환한 예약은 소비할 수 없다.")
    @Test
    void cannotBeConsumedAfterRelease() {
        // arrange
        StockReservationModel reservation = new StockReservationModel(42L);
        reservation.release();

        // act
        boolean consumed = reservation.consume();

        // assert
        assertThat(consumed).isFalse();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    }
}

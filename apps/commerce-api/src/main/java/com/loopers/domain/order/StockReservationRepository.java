package com.loopers.domain.order;

import java.util.List;

public interface StockReservationRepository {
    void holdAll(List<StockReservation> reservations);

    void consumeHeldForOrder(long orderId, int expectedReservations);

    /** 확보 중인 예약을 반환 상태로 바꾸고, 실제 반환할 수량을 상품 ID 순서로 돌려준다. */
    List<StockReservation> releaseHeldForOrder(long orderId, int expectedReservations);
}

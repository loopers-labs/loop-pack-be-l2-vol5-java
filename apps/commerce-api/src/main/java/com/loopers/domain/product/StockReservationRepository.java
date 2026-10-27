package com.loopers.domain.product;

import java.util.List;

public interface StockReservationRepository {
    StockReservation save(StockReservation reservation);

    List<StockReservation> findAllByOrderId(Long orderId);

    /**
     * TX B ⑧ 성공 쪽. WHERE status = 'RESERVED' 일 때만 CONSUMED 로 바꾼다. 재고는 추가 차감하지 않는다.
     *
     * @return 갱신된 행 수
     */
    int consumeIfReserved(Long reservationId);

    /**
     * TX B ⑧ 실패 쪽. WHERE status = 'RESERVED' 일 때만 RELEASED 로 바꾼다.
     * 호출자는 이 값이 1 일 때만 ProductRepository.restoreStock 으로 재고를 1회 복원한다.
     *
     * @return 갱신된 행 수
     */
    int releaseIfReserved(Long reservationId);
}
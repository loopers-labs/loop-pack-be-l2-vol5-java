package com.loopers.domain.order;

import java.util.Optional;

public interface StockReservationRepository {
    StockReservationModel save(StockReservationModel reservation);

    Optional<StockReservationModel> findByOrderId(Long orderId);

    // 주문을 잠근 뒤에 잠근다 (design.md 9-5절 잠금 순서)
    Optional<StockReservationModel> findByOrderIdForUpdate(Long orderId);
}

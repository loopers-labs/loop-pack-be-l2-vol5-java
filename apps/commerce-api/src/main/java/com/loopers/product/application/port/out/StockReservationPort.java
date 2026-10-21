package com.loopers.product.application.port.out;

import com.loopers.product.domain.StockReservation;

import java.util.List;

/**
 * 재고 선점 행의 저장 약속 (ADR-W5-02).
 */
public interface StockReservationPort {

    List<StockReservation> saveAll(List<StockReservation> reservations);

    /**
     * 주문의 선점 행을 product_id 오름차순 쓰기 잠금으로 찾는다. 소비·반환이 쓴다 (ADR-W5-04 잠금 순서: 예약 → 상품).
     */
    List<StockReservation> findByOrderIdForUpdate(Long orderId);

    /**
     * 주문의 선점 행을 잠금 없이 찾는다 (조회용).
     */
    List<StockReservation> findByOrderId(Long orderId);
}

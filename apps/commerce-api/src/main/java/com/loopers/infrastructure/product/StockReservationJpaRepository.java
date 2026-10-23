package com.loopers.infrastructure.product;

import com.loopers.domain.product.StockReservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockReservationJpaRepository extends JpaRepository<StockReservation, Long> {

    List<StockReservation> findAllByOrderId(Long orderId);
}
package com.loopers.infrastructure.order;

import com.loopers.domain.order.StockReservationModel;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StockReservationJpaRepository extends JpaRepository<StockReservationModel, Long> {
    Optional<StockReservationModel> findByOrderId(Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from StockReservationModel r where r.orderId = :orderId")
    Optional<StockReservationModel> findByOrderIdForUpdate(@Param("orderId") Long orderId);
}

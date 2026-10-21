package com.loopers.product.adapter.out.persistence;

import com.loopers.product.domain.StockReservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface StockReservationJpaRepository extends JpaRepository<StockReservation, Long> {

    List<StockReservation> findAllByOrderIdOrderByProductIdAsc(Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM StockReservation r WHERE r.orderId = :orderId ORDER BY r.productId ASC")
    List<StockReservation> findAllByOrderIdForUpdate(@Param("orderId") Long orderId);
}

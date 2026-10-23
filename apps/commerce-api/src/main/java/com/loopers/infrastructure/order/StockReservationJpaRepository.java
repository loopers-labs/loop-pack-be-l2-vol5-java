package com.loopers.infrastructure.order;

import com.loopers.domain.order.StockReservationStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.List;

public interface StockReservationJpaRepository extends JpaRepository<StockReservationJpaEntity, Long> {
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update StockReservationJpaEntity r set r.status = :nextStatus, r.updatedAt = :now "
            + "where r.orderId = :orderId and r.status = :held")
    int changeHeldStatus(
            @Param("orderId") long orderId,
            @Param("held") StockReservationStatus held,
            @Param("nextStatus") StockReservationStatus nextStatus,
            @Param("now") ZonedDateTime now);

    List<StockReservationJpaEntity> findByOrderIdAndStatusOrderByProductIdAsc(
            long orderId, StockReservationStatus status);
}

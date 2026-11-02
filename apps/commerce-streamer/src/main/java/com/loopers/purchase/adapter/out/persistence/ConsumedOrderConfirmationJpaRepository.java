package com.loopers.purchase.adapter.out.persistence;

import com.loopers.purchase.domain.ConsumedOrderConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ConsumedOrderConfirmationJpaRepository extends JpaRepository<ConsumedOrderConfirmation, Long> {

    @Query("SELECT c FROM ConsumedOrderConfirmation c WHERE c.eventId = :eventId OR c.orderId = :orderId")
    List<ConsumedOrderConfirmation> findByEventIdOrOrderId(@Param("eventId") String eventId, @Param("orderId") Long orderId);
}

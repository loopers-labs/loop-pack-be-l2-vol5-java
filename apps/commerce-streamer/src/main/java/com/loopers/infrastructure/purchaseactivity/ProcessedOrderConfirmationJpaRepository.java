package com.loopers.infrastructure.purchaseactivity;

import com.loopers.domain.purchaseactivity.ProcessedOrderConfirmationModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProcessedOrderConfirmationJpaRepository extends JpaRepository<ProcessedOrderConfirmationModel, Long> {

    List<ProcessedOrderConfirmationModel> findByEventIdOrOrderId(String eventId, Long orderId);
}

package com.loopers.domain.purchase;

import java.util.List;

public interface ProcessedOrderConfirmationRepository {

    ProcessedOrderConfirmation saveAndFlush(ProcessedOrderConfirmation confirmation);

    /** eventId 또는 orderId 가 같은 기록. 같은 사실이면 한 건이고, 두 건이면 서로 다른 사실에 걸친 것이다. */
    List<ProcessedOrderConfirmation> findByEventIdOrOrderId(String eventId, Long orderId);
}

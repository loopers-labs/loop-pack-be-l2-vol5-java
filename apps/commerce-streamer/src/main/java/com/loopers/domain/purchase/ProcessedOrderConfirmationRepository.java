package com.loopers.domain.purchase;

import java.util.Optional;

public interface ProcessedOrderConfirmationRepository {

    /**
     * 바로 insert한다(지연 쓰기 없음). event_id·order_id unique에 걸리면 이 호출에서 예외가 난다
     * (docs/week6/decisions.md D-10 — 조회 후 insert가 아니라 DB unique가 판정한다).
     */
    ProcessedOrderConfirmation insert(ProcessedOrderConfirmation processed);

    Optional<ProcessedOrderConfirmation> findByEventId(String eventId);

    Optional<ProcessedOrderConfirmation> findByOrderId(Long orderId);
}

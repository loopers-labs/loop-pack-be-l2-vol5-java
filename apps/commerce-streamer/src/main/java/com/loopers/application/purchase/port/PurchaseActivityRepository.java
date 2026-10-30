package com.loopers.application.purchase.port;

import com.loopers.application.purchase.OrderConfirmedEvent;
import com.loopers.application.purchase.PurchasedItem;
import java.time.Instant;
import java.util.Optional;

public interface PurchaseActivityRepository {

    record ProcessedConfirmation(String eventId, long orderId, String fingerprint) {
    }

    Optional<ProcessedConfirmation> findConfirmationByEventId(String eventId);

    Optional<ProcessedConfirmation> findConfirmationByOrderId(long orderId);

    /**
     * 같은 eventId 또는 orderId의 완료 기록이 이미 있으면 {@link DuplicateConfirmationException}을 던진다.
     */
    void saveConfirmation(OrderConfirmedEvent event, Instant processedAt);

    void saveActivity(long orderId, PurchasedItem item, Instant confirmedAt, Instant createdAt);
}

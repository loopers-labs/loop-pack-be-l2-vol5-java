package com.loopers.domain.activity;

import java.util.Optional;

public interface PurchaseActivityRepository {

    void saveReceipt(ConfirmedPurchase purchase);

    void saveActivities(ConfirmedPurchase purchase);

    Optional<RecordedConfirmation> findReceiptByEventId(String eventId);

    Optional<RecordedConfirmation> findReceiptByOrderId(Long orderId);

    void saveHold(OrderEventHold hold);
}

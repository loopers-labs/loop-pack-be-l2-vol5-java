package com.loopers.infrastructure.activity;

import com.loopers.domain.activity.ConfirmedPurchase;
import com.loopers.domain.activity.PurchaseActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class PurchaseActivityRepositoryImpl implements PurchaseActivityRepository {

    private final OrderConfirmationReceiptJpaRepository receiptJpaRepository;
    private final PurchaseActivityJpaRepository activityJpaRepository;

    @Override
    public void saveReceipt(ConfirmedPurchase purchase) {
        receiptJpaRepository.saveAndFlush(OrderConfirmationReceiptEntity.of(purchase));
    }

    @Override
    public void saveActivities(ConfirmedPurchase purchase) {
        activityJpaRepository.saveAllAndFlush(purchase.items().stream()
            .map(item -> PurchaseActivityEntity.of(
                purchase.orderId(), item.productId(), item.quantity(), purchase.confirmedAt()))
            .toList());
    }
}

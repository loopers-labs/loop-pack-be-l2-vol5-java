package com.loopers.application.purchase;

import com.loopers.domain.purchase.OrderPurchase;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.ProcessedOrderConfirmationRepository;
import com.loopers.domain.purchase.PurchaseActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PurchaseActivityRecorder {
    private final PurchaseActivityRepository activities;
    private final ProcessedOrderConfirmationRepository confirmations;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(OrderPurchase purchase) {
        if (verifyExisting(purchase)) {
            return;
        }
        confirmations.save(new ProcessedOrderConfirmation(purchase));
        for (OrderPurchase.Item item : purchase.items()) {
            activities.save(new ProductPurchaseActivity(
                purchase.orderId(), item.productId(), item.quantity(), purchase.confirmedAt()));
        }
    }

    /** 실패한 쓰기 TX가 rollback된 뒤 새 TX에서 승자가 저장한 사실을 확인한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean verifyRecorded(OrderPurchase purchase) {
        return verifyExisting(purchase);
    }

    private boolean verifyExisting(OrderPurchase purchase) {
        var existing = confirmations.findByEventId(purchase.eventId())
            .or(() -> confirmations.findByOrderId(purchase.orderId()));
        existing.ifPresent(confirmation -> confirmation.verifySameFact(purchase));
        return existing.isPresent();
    }
}

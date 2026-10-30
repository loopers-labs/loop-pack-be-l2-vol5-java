package com.loopers.application.purchase;

import com.loopers.domain.purchase.OrderConfirmation;
import com.loopers.domain.purchase.PurchaseActivityConflictException;
import com.loopers.domain.purchase.PurchaseActivityStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurchaseActivityTransactions {
    private final PurchaseActivityStore store;

    public PurchaseActivityTransactions(PurchaseActivityStore store) {
        this.store = store;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void insert(OrderConfirmation confirmation) {
        store.insertConfirmation(confirmation);
        for (OrderConfirmation.Item item : confirmation.items()) {
            store.insertActivity(confirmation.orderId(), confirmation.confirmedAt(), item);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, readOnly = true)
    public void verifyIdentical(OrderConfirmation confirmation) {
        var byEvent = store.findByEventId(confirmation.eventId());
        var byOrder = store.findByOrderId(confirmation.orderId());
        if (byEvent.filter(confirmation::equals).isEmpty() || byOrder.filter(confirmation::equals).isEmpty()) {
            throw new PurchaseActivityConflictException();
        }
    }
}

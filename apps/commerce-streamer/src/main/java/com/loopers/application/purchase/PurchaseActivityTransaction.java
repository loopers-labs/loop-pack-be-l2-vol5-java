package com.loopers.application.purchase;

import com.loopers.domain.purchase.OrderConfirmation;
import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.PurchaseActivityRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PurchaseActivityTransaction {
    private final PurchaseActivityRepository repository;

    public PurchaseActivityTransaction(PurchaseActivityRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void consume(OrderConfirmation confirmation) {
        var existing = repository.findProcessed(confirmation.eventId(), confirmation.orderId());
        if (!existing.isEmpty()) {
            confirmation.verifyExisting(existing, repository.findActivities(confirmation.orderId()));
            return;
        }
        repository.saveCompletion(ProcessedOrderConfirmation.create(confirmation.eventId(), confirmation.orderId()));
        confirmation.activities().forEach(repository::saveActivity);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean verifyExisting(OrderConfirmation confirmation) {
        var existing = repository.findProcessed(confirmation.eventId(), confirmation.orderId());
        if (existing.isEmpty()) {
            return false;
        }
        confirmation.verifyExisting(existing, repository.findActivities(confirmation.orderId()));
        return true;
    }
}

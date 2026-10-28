package com.loopers.application.purchase;

import com.loopers.domain.purchase.OrderConfirmation;
import com.loopers.domain.purchase.DuplicateOrderConfirmationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PurchaseActivityHandler {
    private final PurchaseActivityTransaction transaction;

    public PurchaseActivityHandler(PurchaseActivityTransaction transaction) {
        this.transaction = transaction;
    }

    @Transactional(propagation = Propagation.NEVER)
    public void consume(OrderConfirmation confirmation) {
        try {
            transaction.consume(confirmation);
        } catch (DuplicateOrderConfirmationException e) {
            if (!transaction.verifyExisting(confirmation)) {
                throw e;
            }
        }
    }
}

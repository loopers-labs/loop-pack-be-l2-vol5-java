package com.loopers.application.purchase;

import com.loopers.domain.purchase.DuplicateOrderConfirmationException;
import com.loopers.domain.purchase.OrderConfirmationConflictException;
import com.loopers.domain.purchase.OrderPurchase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderConfirmationHandler {
    private final PurchaseActivityRecorder recorder;

    public void handle(OrderConfirmationCommand command) {
        try {
            handle(command.purchase());
        } catch (OrderConfirmationConflictException conflict) {
            throw new ConflictingOrderConfirmationException(conflict);
        }
    }

    public void handle(OrderPurchase purchase) {
        try {
            recorder.record(purchase);
        } catch (DuplicateOrderConfirmationException competition) {
            if (!recorder.verifyRecorded(purchase)) {
                throw competition;
            }
        }
    }
}

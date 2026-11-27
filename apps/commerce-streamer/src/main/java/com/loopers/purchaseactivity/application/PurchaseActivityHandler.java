package com.loopers.purchaseactivity.application;

import com.loopers.kafka.event.OrderConfirmed;
import com.loopers.purchaseactivity.domain.DuplicateConfirmationException;

import org.springframework.stereotype.Service;

@Service
public class PurchaseActivityHandler {
    private final PurchaseActivityTransactions transactions;

    public PurchaseActivityHandler(PurchaseActivityTransactions transactions) {
        this.transactions = transactions;
    }

    public PurchaseActivityTransactions.RecordOutcome consume(OrderConfirmed event) {
        try {
            return transactions.record(event);
        } catch (DuplicateConfirmationException concurrentDuplicate) {
            transactions.verifyAlreadyRecorded(event);
            return PurchaseActivityTransactions.RecordOutcome.DUPLICATE;
        }
    }
}

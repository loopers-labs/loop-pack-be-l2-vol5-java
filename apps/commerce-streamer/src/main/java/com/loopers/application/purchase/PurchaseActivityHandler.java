package com.loopers.application.purchase;

import com.loopers.domain.purchase.ConfirmationAlreadyRecordedException;
import com.loopers.domain.purchase.OrderConfirmation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/** Returns only after the write transaction commits or a fresh transaction verifies an identical replay. */
@Service
public class PurchaseActivityHandler {
    private final PurchaseActivityTransactions transactions;

    public PurchaseActivityHandler(PurchaseActivityTransactions transactions) {
        this.transactions = transactions;
    }

    public Result consume(OrderConfirmation confirmation) {
        Objects.requireNonNull(confirmation, "confirmation");
        if (TransactionSynchronizationManager.isActualTransactionActive()
            || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Purchase consumption must start outside an existing transaction");
        }
        try {
            transactions.insert(confirmation);
            return Result.APPLIED;
        } catch (ConfirmationAlreadyRecordedException duplicateCandidate) {
            // The separate proxied write transaction has already rolled back here.
            transactions.verifyIdentical(confirmation);
            return Result.DUPLICATE;
        }
    }

    public enum Result {
        APPLIED, DUPLICATE
    }
}

package com.loopers.purchaseactivity.application;

import com.loopers.kafka.event.OrderConfirmed;
import com.loopers.purchaseactivity.domain.ConfirmationConflictException;
import com.loopers.purchaseactivity.domain.ProcessedOrderConfirmation;
import com.loopers.purchaseactivity.domain.ProcessedOrderConfirmationRepository;
import com.loopers.purchaseactivity.domain.PurchaseActivity;
import com.loopers.purchaseactivity.domain.PurchaseActivityRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Collectors;

@Service
public class PurchaseActivityTransactions {
    private final ProcessedOrderConfirmationRepository confirmations;
    private final PurchaseActivityRepository activities;

    public PurchaseActivityTransactions(
            ProcessedOrderConfirmationRepository confirmations, PurchaseActivityRepository activities) {
        this.confirmations = confirmations;
        this.activities = activities;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void record(OrderConfirmed event) {
        ProcessedOrderConfirmation confirmation = confirmationOf(event);
        if (matchesExisting(confirmation)) {
            return;
        }
        confirmations.save(confirmation);
        for (OrderConfirmed.Item item : event.items()) {
            activities.save(new PurchaseActivity(event.orderId(), item.productId(), item.quantity(), event.confirmedAt()));
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void verifyAlreadyRecorded(OrderConfirmed event) {
        if (!matchesExisting(confirmationOf(event))) {
            throw new IllegalStateException("Concurrent confirmation was not found after transaction rollback");
        }
    }

    private boolean matchesExisting(ProcessedOrderConfirmation expected) {
        var existing = confirmations.findByEventIdOrOrderId(expected.eventId(), expected.orderId());
        if (existing.isEmpty()) {
            return false;
        }
        if (existing.size() == 1 && existing.getFirst().equals(expected)) {
            return true;
        }
        throw new ConfirmationConflictException(expected.eventId(), expected.orderId());
    }

    private ProcessedOrderConfirmation confirmationOf(OrderConfirmed event) {
        String items = event.items().stream()
                .map(item -> item.productId() + ":" + item.quantity())
                .collect(Collectors.joining(";"));
        return new ProcessedOrderConfirmation(event.eventId(), event.orderId(), event.confirmedAt(), items);
    }
}

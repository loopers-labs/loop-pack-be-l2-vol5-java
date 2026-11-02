package com.loopers.purchaseactivity.application;

import com.loopers.kafka.event.OrderConfirmed;
import com.loopers.purchaseactivity.domain.DuplicateConfirmationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PurchaseActivityHandler {
    private static final Logger log = LoggerFactory.getLogger(PurchaseActivityHandler.class);

    private final PurchaseActivityTransactions transactions;

    public PurchaseActivityHandler(PurchaseActivityTransactions transactions) {
        this.transactions = transactions;
    }

    public void consume(OrderConfirmed event) {
        try {
            transactions.record(event);
        } catch (DuplicateConfirmationException concurrentDuplicate) {
            transactions.verifyAlreadyRecorded(event);
            log.info("Concurrent duplicate confirmation verified: eventId={}, orderId={}",
                    event.eventId(), event.orderId());
        }
    }
}

package com.loopers.domain.activity;

public sealed interface PurchaseReading {

    record Readable(ConfirmedPurchase purchase) implements PurchaseReading {
    }

    record Unreadable(UnreadableReason reason) implements PurchaseReading {
    }
}

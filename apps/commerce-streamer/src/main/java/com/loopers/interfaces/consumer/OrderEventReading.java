package com.loopers.interfaces.consumer;

import com.loopers.domain.activity.PurchaseReading;

sealed interface OrderEventReading {

    record Confirmed(PurchaseReading reading) implements OrderEventReading {
    }

    record NotHandled(String eventType) implements OrderEventReading {
    }
}

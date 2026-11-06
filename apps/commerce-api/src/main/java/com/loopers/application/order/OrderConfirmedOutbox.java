package com.loopers.application.order;

import com.loopers.kafka.event.OrderConfirmed;

public interface OrderConfirmedOutbox {
    void record(OrderConfirmed event);

    boolean recordIfAbsent(OrderConfirmed event);
}

package com.loopers.application.order;

public interface OrderOutbox {
    void record(OrderOutboxMessage event);

    boolean recordIfAbsent(OrderOutboxMessage event);
}

package com.loopers.order.application.outbox;

import com.loopers.order.application.event.OrderConfirmedEvent;

public interface OrderConfirmedOutboxWriter {

    void write(OrderConfirmedEvent event);
}

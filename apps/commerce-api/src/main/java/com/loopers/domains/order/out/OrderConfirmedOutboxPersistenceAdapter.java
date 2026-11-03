package com.loopers.domains.order.out;

import com.loopers.domains.order.app.port.OrderConfirmedOutbox;
import com.loopers.domains.order.app.port.OrderConfirmedPayload;
import org.springframework.stereotype.Repository;

@Repository
public class OrderConfirmedOutboxPersistenceAdapter implements OrderConfirmedOutbox {

    @Override
    public void append(OrderConfirmedPayload payload) {
    }
}

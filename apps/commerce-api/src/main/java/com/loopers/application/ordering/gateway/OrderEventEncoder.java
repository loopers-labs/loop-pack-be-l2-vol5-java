package com.loopers.application.ordering.gateway;

import com.loopers.application.ordering.event.OrderConfirmedEvent;

public interface OrderEventEncoder {
    byte[] encodePayload(OrderConfirmedEvent event);

    boolean samePayload(byte[] expected, byte[] stored);
}

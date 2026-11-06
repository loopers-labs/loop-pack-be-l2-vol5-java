package com.loopers.application.order;

public interface OrderEventPublisher {
    void publishOrderConfirmed(OrderConfirmedEvent event);
}

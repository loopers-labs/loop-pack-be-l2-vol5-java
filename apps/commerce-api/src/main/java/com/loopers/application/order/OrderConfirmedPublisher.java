package com.loopers.application.order;

import com.loopers.kafka.event.OrderConfirmed;

public interface OrderConfirmedPublisher {
    PublishedRecord publish(OrderConfirmed event);

    record PublishedRecord(String topic, int partition, long offset) {}
}

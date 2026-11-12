package com.loopers.application.order;

public interface OutboxPublisher {

    void publish(String topic, String messageKey, String payload) throws Exception;
}

package com.loopers.application.order;

public interface OutboxKafkaSender {
    void send(String topic, String key, String payload);
}

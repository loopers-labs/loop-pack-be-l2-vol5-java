package com.loopers.infrastructure.purchase;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.kafka.listener.ContainerProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "order.confirmed-event.consumer")
public record PurchaseActivityConsumerProperties(
    int concurrency,
    String autoOffsetReset,
    boolean allowAutoCreateTopics,
    int fetchMinBytes,
    Duration fetchMaxWait,
    ContainerProperties.AckMode ackMode,
    boolean syncCommits
) {}

package com.loopers.config.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

@ConfigurationProperties(prefix = "kafka.batch-listener")
public record BatchListenerProperties(
    int maxPollRecords,
    DataSize fetchMinSize,
    Duration fetchMaxWait,
    Duration sessionTimeout,
    Duration heartbeatInterval,
    Duration maxPollInterval
) {}

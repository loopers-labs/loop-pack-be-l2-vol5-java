package com.loopers.experiment.queue;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "experiment")
public record ExperimentProperties(
    int semaphoreCapacity,
    int workerCount,
    long workDurationMs,
    long pollIntervalMs,
    long jobRetentionSeconds,
    int maxQueueSize,
    int memoryBytesPerJob,
    int diskBytesPerJob,
    int imageTransformsPerJob,
    String scratchDirectory
) {
    public ExperimentProperties {
        if (semaphoreCapacity < 1 || workerCount < 1 || workDurationMs < 1
            || pollIntervalMs < 1 || jobRetentionSeconds < 1 || maxQueueSize < 1
            || memoryBytesPerJob < 1 || diskBytesPerJob < 1 || imageTransformsPerJob < 1) {
            throw new IllegalArgumentException("experiment configuration values must be positive");
        }
    }
}

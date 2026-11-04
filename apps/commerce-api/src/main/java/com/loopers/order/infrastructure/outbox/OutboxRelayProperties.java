package com.loopers.order.infrastructure.outbox;

import java.time.Duration;

public class OutboxRelayProperties {

    private Duration pollInterval = Duration.ofSeconds(1);
    private int batchSize = 10;
    private int maxAttempts = 3;

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }
}

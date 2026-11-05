package com.loopers.batch.job.reconcile;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("commerce.reconcile")
public record ReconcileProperties(Duration grace, Duration overlap, int chunkSize, Duration sendTimeout) {
}

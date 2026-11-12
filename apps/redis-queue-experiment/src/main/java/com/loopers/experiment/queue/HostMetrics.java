package com.loopers.experiment.queue;

public record HostMetrics(
    double systemCpuPercent,
    double processCpuPercent,
    long availableMemoryBytes,
    long totalMemoryBytes,
    long heapUsedBytes,
    long heapMaxBytes,
    long diskUsableBytes,
    long diskTotalBytes,
    long processDiskReadBytes,
    long processDiskWriteBytes
) {
}

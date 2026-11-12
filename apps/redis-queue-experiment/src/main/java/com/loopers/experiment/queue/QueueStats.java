package com.loopers.experiment.queue;

public record QueueStats(
    long queuedJobs,
    long activeJobs,
    int semaphoreCapacity,
    int workerCount
) {
}

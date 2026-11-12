package com.loopers.experiment.queue;

public record JobEnqueued(String jobId, String status, WorkloadType workloadType) {
}

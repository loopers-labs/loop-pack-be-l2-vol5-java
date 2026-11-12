package com.loopers.experiment.queue;

public record JobView(
    String jobId,
    String status,
    WorkloadType workloadType,
    String submittedAt,
    String startedAt,
    String completedAt,
    String error
) {
}

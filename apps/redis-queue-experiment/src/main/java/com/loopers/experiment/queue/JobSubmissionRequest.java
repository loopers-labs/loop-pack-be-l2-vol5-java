package com.loopers.experiment.queue;

public record JobSubmissionRequest(WorkloadType type) {

    public WorkloadType resolvedType() {
        return type == null ? WorkloadType.CPU : type;
    }
}

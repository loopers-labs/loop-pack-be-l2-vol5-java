package com.loopers.experiment.queue;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/experiment/jobs")
public class JobController {

    private final JobQueue jobQueue;

    public JobController(JobQueue jobQueue) {
        this.jobQueue = jobQueue;
    }

    @PostMapping
    public ResponseEntity<JobEnqueued> enqueue(@RequestBody(required = false) JobSubmissionRequest request) {
        WorkloadType type = request == null ? WorkloadType.CPU : request.resolvedType();
        String jobId = jobQueue.enqueue(type);
        return ResponseEntity.accepted()
            .location(URI.create("/experiment/jobs/" + jobId))
            .body(new JobEnqueued(jobId, "QUEUED", type));
    }

    @GetMapping("/{jobId}")
    public JobView find(@PathVariable String jobId) {
        JobView job = jobQueue.find(jobId);
        if (job == null) {
            throw new ResponseStatusException(NOT_FOUND, "job not found or expired");
        }
        return job;
    }

    @GetMapping("/stats")
    public QueueStats stats() {
        return jobQueue.stats();
    }

}

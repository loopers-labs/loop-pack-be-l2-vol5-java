package com.loopers.experiment.queue;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class JobWorkerPool {

    private static final Logger log = LoggerFactory.getLogger(JobWorkerPool.class);

    private final JobQueue jobQueue;
    private final WorkloadRunner workloadRunner;
    private final ExperimentProperties properties;
    private ExecutorService workers;

    public JobWorkerPool(JobQueue jobQueue, WorkloadRunner workloadRunner, ExperimentProperties properties) {
        this.jobQueue = jobQueue;
        this.workloadRunner = workloadRunner;
        this.properties = properties;
    }

    @PostConstruct
    void start() {
        workers = Executors.newFixedThreadPool(properties.workerCount(), task -> {
            Thread thread = new Thread(task);
            thread.setName("experiment-worker-" + thread.threadId());
            thread.setDaemon(false);
            return thread;
        });
        for (int i = 0; i < properties.workerCount(); i++) {
            workers.submit(this::runWorker);
        }
        log.info("Started {} workers; global semaphore capacity is {}", properties.workerCount(),
            properties.semaphoreCapacity());
    }

    @PreDestroy
    void stop() {
        if (workers != null) {
            workers.shutdownNow();
        }
    }

    private void workLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                JobQueue.ClaimedJob job = jobQueue.claim();
                if (job == null) {
                    pause();
                    continue;
                }
                process(job);
            } catch (RuntimeException exception) {
                log.warn("Worker could not claim a job; will retry", exception);
                pause();
            }
        }
    }

    private void runWorker() {
        try {
            workLoop();
        } catch (OutOfMemoryError error) {
            // submit() captures task failures in its Future, so the thread's uncaught
            // exception handler would not see this unless we log it here.
            log.error("Worker stopped after OutOfMemoryError; active job permits may remain held", error);
            throw error;
        }
    }

    private void process(JobQueue.ClaimedJob job) {
        log.info("Started job {}", job.jobId());
        try {
            workloadRunner.run(job.workloadType());
            jobQueue.finish(job, "COMPLETED", null);
            log.info("Completed job {}", job.jobId());
        } catch (InterruptedException exception) {
            jobQueue.finish(job, "FAILED", "worker interrupted");
            Thread.currentThread().interrupt();
        } catch (IOException exception) {
            jobQueue.finish(job, "FAILED", exception.getClass().getSimpleName());
            log.error("Failed job {}", job.jobId(), exception);
        } catch (RuntimeException exception) {
            jobQueue.finish(job, "FAILED", exception.getClass().getSimpleName());
            log.error("Failed job {}", job.jobId(), exception);
        }
    }

    private void pause() {
        try {
            Thread.sleep(properties.pollIntervalMs());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}

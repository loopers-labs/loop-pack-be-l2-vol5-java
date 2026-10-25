package com.loopers.testpg.infrastructure;

import com.loopers.testpg.application.PaymentScheduler;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class DelayedPaymentScheduler implements PaymentScheduler {
    private final TaskScheduler scheduler;

    public DelayedPaymentScheduler(TaskScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public void schedule(Runnable task, Duration delay) {
        scheduler.schedule(task, Instant.now().plus(delay));
    }
}

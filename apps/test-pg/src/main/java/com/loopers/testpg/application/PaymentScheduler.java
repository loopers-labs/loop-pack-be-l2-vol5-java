package com.loopers.testpg.application;

import java.time.Duration;

public interface PaymentScheduler {
    void schedule(Runnable task, Duration delay);
}

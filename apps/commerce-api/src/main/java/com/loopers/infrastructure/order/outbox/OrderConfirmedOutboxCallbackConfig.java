package com.loopers.infrastructure.order.outbox;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class OrderConfirmedOutboxCallbackConfig {

    @Bean(name = "orderConfirmedOutboxCallbackExecutor", destroyMethod = "shutdown")
    public ExecutorService orderConfirmedOutboxCallbackExecutor() {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "order-confirmed-outbox-callback");
            thread.setDaemon(true);
            return thread;
        });
    }
}

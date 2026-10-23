package com.loopers.infrastructure.resilience.pay;

import com.loopers.application.pay.gateway.PaymentRecoveryExecutor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class BoundedPaymentRecoveryExecutor implements PaymentRecoveryExecutor {
    private final ThreadPoolTaskExecutor executor;

    public BoundedPaymentRecoveryExecutor(@Qualifier("paymentRecoveryExecutor") ThreadPoolTaskExecutor executor) {
        this.executor = executor;
    }

    @Override
    public void submit(Runnable task) {
        try { executor.execute(task); }
        catch (RejectedExecutionException failure) { throw new SubmissionRejectedException(failure); }
    }
}

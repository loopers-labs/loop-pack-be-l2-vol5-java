package com.loopers.config.pay;

import com.loopers.application.ordering.support.RepeatPaymentLookupException;
import com.loopers.application.pay.gateway.PaymentRecoveryRuntime;
import io.github.resilience4j.common.retry.configuration.RetryConfigCustomizer;
import io.github.resilience4j.core.IntervalBiFunction;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AutomaticPaymentRecoveryConfig {
    @Bean
    public ThreadPoolTaskExecutor paymentRecoveryExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(2);
        executor.setThreadNamePrefix("payment-recovery-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    @Bean
    public RetryConfigCustomizer automaticPaymentLookupIntervals(PaymentRecoveryRuntime runtime) {
        IntervalBiFunction<Object> intervals = (attempt, result) -> {
            if (!result.isLeft() || !(result.getLeft() instanceof RepeatPaymentLookupException repeat)) {
                throw new IllegalStateException("자동 조회의 재조회 사유가 필요합니다.");
            }
            return repeat.execution().retryDelayMillis(runtime.randomUnit(), runtime.nanoTime());
        };
        return RetryConfigCustomizer.of("payment-auto-lookup", builder -> builder.intervalBiFunction(intervals));
    }
}

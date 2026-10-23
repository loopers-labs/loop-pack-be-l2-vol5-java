package com.loopers.config;

import com.loopers.payment.application.PaymentSender;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 결제 발송 전용 스레드풀 (격벽, ADR-W5-01·03). 다른 비동기 작업과 섞지 않는다.
 * {@code @Async(PaymentSender.EXECUTOR)}가 이 풀을 쓴다. @EnableAsync가 없으면 @Async가 무시되어 요청 스레드에서 동기로 보낸다.
 * 가득 차면 RejectedExecutionException(TaskRejectedException) → PaymentDispatcher가 FAILED(NOT_SENT)로 닫는다.
 */
@EnableAsync
@Configuration
@EnableConfigurationProperties(PaymentDispatchProperties.class)
public class PaymentDispatchConfig {

    @Bean(name = PaymentSender.EXECUTOR)
    ThreadPoolTaskExecutor paymentDispatchExecutor(PaymentDispatchProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.poolSize());
        executor.setMaxPoolSize(properties.poolSize());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("payment-dispatch-");
        // 종료 때 진행 중인 발송은 결과 기록까지 기다린다. 못 끝낸 건은 정리 스케줄러가 맡는다.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}

package com.loopers.config;

import com.loopers.outbox.application.OutboxPublishService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * send 콜백 결과를 DB에 표시하는 전용 스레드풀 (ADR-W6-03). producer I/O 스레드에서 DB를 쓰지 않으려고 둔다.
 * 커넥션은 이 풀 크기(2)만큼만 쓴다. 큐가 차서 표시를 못 하면 published_at이 null로 남아 "미발행"으로 보인다 —
 * W7 relay가 다시 보내도 consumer가 멱등이라 안전한 쪽으로 틀린다.
 */
@Slf4j
@Configuration
public class OutboxResultExecutorConfig {

    @Bean(name = OutboxPublishService.RESULT_EXECUTOR)
    ThreadPoolTaskExecutor outboxResultExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10_000);
        executor.setThreadNamePrefix("outbox-result-");
        executor.setRejectedExecutionHandler((task, pool) -> log.error("발행 결과 표시 큐가 가득 찼습니다 — 미발행으로 남습니다."));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}

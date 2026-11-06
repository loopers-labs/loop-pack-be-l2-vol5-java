package com.loopers.config.scheduling;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Outbox relay 전용 단일 스레드 스케줄러 (7주차 설계 D22).
 * Spring 기본 스케줄러는 스레드 1개를 모든 @Scheduled 가 나눠 쓰므로, 결제 조회(PaymentReconcileScheduler)와 relay 가 서로를 지연시킬 수 있다.
 * relay 는 자기 스케줄러에서 돌리고, fixedDelay 라 같은 relay tick 은 겹치지 않는다.
 *
 * TaskScheduler 빈을 하나라도 정의하면 Boot 의 기본 taskScheduler 자동 설정이 꺼지므로, 기본 스케줄러도 여기서 같은 builder 로 다시 만든다
 * (spring.task.scheduling.* 설정이 그대로 적용된다). @Primary 를 붙여 scheduler 를 지정하지 않은 기존 @Scheduled 는 계속 기본 스케줄러를 쓴다.
 */
@Configuration
public class OutboxRelaySchedulingConfig {
    public static final String OUTBOX_RELAY_TASK_SCHEDULER = "outboxRelayTaskScheduler";

    @Primary
    @Bean
    public ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }

    @Bean(name = OUTBOX_RELAY_TASK_SCHEDULER)
    public ThreadPoolTaskScheduler outboxRelayTaskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.poolSize(1).threadNamePrefix("outbox-relay-").build();
    }
}

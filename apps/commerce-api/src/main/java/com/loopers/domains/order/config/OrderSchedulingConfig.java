package com.loopers.domains.order.config;

import com.loopers.domains.order.in.OrderOutboxRelayScheduler;
import com.loopers.domains.order.in.PaymentLookupScheduler;
import com.loopers.domains.order.in.UnresolvedPaymentMetricsScheduler;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

// 스케줄 작업마다 전용 단일 스레드를 둔다(격벽, 8주차 28절 "풀 2" 대체). 한 작업이 PG·broker 대기로 길게 막혀도 다른 작업은 제 주기에 돈다.
// 새 스케줄 작업도 전용 스케줄러를 하나 더 만들어 @Scheduled(scheduler)로 붙인다. 켜기 설정과 무관하게 항상 만든다(스레드는 첫 작업 때 생겨 꺼진 작업엔 없다).
// Boot builder를 써서 spring.task.scheduling.shutdown.* 설정이 그대로 적용된다. 이 빈들이 있으면 Boot 기본 taskScheduler는 만들어지지 않는다.
// 이 빈들은 Executor이기도 해서 Boot 기본 applicationTaskExecutor도 만들어지지 않는다. @Async를 쓰게 되면 executor 빈을 따로 둔다.
// 남는 공유 자원: DB 연결 풀과 PG는 여전히 작업끼리 나눠 쓴다.
@Configuration
public class OrderSchedulingConfig {
    @Bean(PaymentLookupScheduler.TASK_SCHEDULER)
    ThreadPoolTaskScheduler paymentLookupTaskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return dedicated(builder, "sched-payment-lookup-");
    }

    @Bean(OrderOutboxRelayScheduler.TASK_SCHEDULER)
    ThreadPoolTaskScheduler outboxRelayTaskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return dedicated(builder, "sched-outbox-relay-");
    }

    @Bean(UnresolvedPaymentMetricsScheduler.TASK_SCHEDULER)
    ThreadPoolTaskScheduler unresolvedMetricTaskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return dedicated(builder, "sched-unresolved-metric-");
    }

    private static ThreadPoolTaskScheduler dedicated(ThreadPoolTaskSchedulerBuilder builder, String threadNamePrefix) {
        return builder.poolSize(1).threadNamePrefix(threadNamePrefix).build();
    }
}

package com.loopers.domains.payment.config;

import com.loopers.domains.payment.in.RefundTrackingScheduler;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

// OrderSchedulingConfig와 같은 규칙의 결제 쪽 전용 스케줄러다. 업무 config는 자기 업무만 알아야 해서(ArchUnit 7(a)) 결제 config에 둔다.
@Configuration
public class PaymentSchedulingConfig {
    @Bean(RefundTrackingScheduler.TASK_SCHEDULER)
    ThreadPoolTaskScheduler paymentRefundTaskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.poolSize(1).threadNamePrefix("sched-payment-refund-").build();
    }
}

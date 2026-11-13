package com.loopers.config.coupon;

import com.loopers.application.coupon.CouponIssuanceService;
import com.loopers.application.coupon.CouponIssuanceWorker;
import com.loopers.application.coupon.CouponIssueSemaphore;
import com.loopers.interfaces.scheduler.CouponIssuanceSchedule;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "commerce.coupon.worker.enabled", havingValue = "true")
@EnableScheduling
public class CouponIssuanceWorkerConfiguration {
    @Bean
    ThreadPoolTaskExecutor couponIssuanceExecutor(@Value("${commerce.coupon.worker.capacity:3}") int capacity) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(capacity);
        executor.setMaxPoolSize(capacity);
        executor.setQueueCapacity(capacity);
        executor.setThreadNamePrefix("coupon-issue-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    @Bean
    ThreadPoolTaskScheduler couponIssuanceScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("coupon-issue-poll-");
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }

    @Bean(destroyMethod = "close")
    CouponIssuanceWorker couponIssuanceWorker(CouponIssuanceService issuance, CouponIssueSemaphore slots,
            @Qualifier("couponIssuanceExecutor") ThreadPoolTaskExecutor executor,
            @Value("${commerce.coupon.worker.capacity:3}") int capacity) {
        return new CouponIssuanceWorker(issuance, slots, executor, capacity);
    }

    @Bean
    CouponIssuanceSchedule couponIssuanceSchedule(CouponIssuanceWorker worker,
            @Value("${commerce.coupon.worker.campaign-ids}") List<Long> campaigns) {
        if (campaigns.isEmpty() || campaigns.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Configure positive coupon campaign IDs before enabling the issuance worker");
        }
        return new CouponIssuanceSchedule(worker, campaigns.stream().distinct().toList());
    }
}

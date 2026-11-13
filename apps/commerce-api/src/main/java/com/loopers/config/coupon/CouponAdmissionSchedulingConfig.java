package com.loopers.config.coupon;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 쿠폰 수용량 통제 전용 스케줄러 (8주차 설계 §5-2·§7-2, C11).
 * 하트비트(10초)·승격(200ms)·gate probe(5초)는 모두 작은 Redis 단일 명령이라 스레드 1개로 충분하다.
 * 기본 스케줄러(스레드 1개)를 함께 쓰면 결제 조회(수 초)가 승격을 막으므로 분리한다. 기본 스케줄러 재정의는 OutboxRelaySchedulingConfig 가 맡는다.
 */
@Configuration
public class CouponAdmissionSchedulingConfig {

    public static final String COUPON_ADMISSION_TASK_SCHEDULER = "couponAdmissionTaskScheduler";

    @Bean(name = COUPON_ADMISSION_TASK_SCHEDULER)
    public ThreadPoolTaskScheduler couponAdmissionTaskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.poolSize(1).threadNamePrefix("coupon-admission-").build();
    }
}

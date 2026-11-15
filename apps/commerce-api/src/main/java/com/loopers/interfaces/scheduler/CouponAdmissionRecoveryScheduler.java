package com.loopers.interfaces.scheduler;

import com.loopers.application.coupon.SemaphoreGate;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 기동 직후부터 일정 간격으로 직행 중인 수용을 재개 조건에 따라 되돌리고, 자기 token을 정리한다(미반환 token 재시도, 이전 프로세스가 남긴 고아 정리).
 * 자체 스케줄러 스레드 하나를 쓰므로 회차가 겹치지 않고, 공용 스케줄링 설정(Outbox relay)에 기대지 않는다.
 */
@Component
@ConditionalOnProperty(name = "coupon.admission.recovery.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class CouponAdmissionRecoveryScheduler {

    private final SemaphoreGate semaphoreGate;
    private final Duration fixedDelay;
    private ThreadPoolTaskScheduler scheduler;

    public CouponAdmissionRecoveryScheduler(
        SemaphoreGate semaphoreGate,
        @Value("${coupon.admission.recovery.fixed-delay:5s}") Duration fixedDelay
    ) {
        this.semaphoreGate = semaphoreGate;
        this.fixedDelay = fixedDelay;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("coupon-admission-recovery-");
        scheduler.initialize();
        scheduler.scheduleWithFixedDelay(this::tick, fixedDelay);
    }

    /** 한 회차: 직행 중이면 재개 조건을 확인하고, 아니면 자기 token을 정리한다. 어느 쪽이든 예외 없이 끝난다. */
    void tick() {
        semaphoreGate.tryResume();
        semaphoreGate.recoverOwnTokens();
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }
}

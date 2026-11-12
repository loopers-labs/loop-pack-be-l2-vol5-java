package com.loopers.coupon.adapter.in.scheduler;

import com.loopers.coupon.application.QueueAdmissionService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 입장 스케줄러 (D-4-3). Spring 기본 스케줄러 스레드 1개를 outbox relay(최대 30초)·결제·포인트 작업과 같이 쓰면
 * 그동안 입장이 멈추므로, 자기 전용 스레드 1개(queue-admission-1)를 쓴다. fixedDelay라 앞 실행이 끝난 뒤 다음을 센다.
 * 1개면 되는 이유: 한 번에 하는 일이 열린 캠페인마다 Lua 한 번이고, 구간 한도는 Redis 카운터가 세서 스레드를 늘려도 더 들어가지 않는다.
 */
@ConditionalOnProperty(name = "coupon.queue.admission.scheduler.enabled", havingValue = "true")
@Component
public class QueueAdmissionScheduler {

    private final QueueAdmissionService admissionService;
    private final Duration tick;
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    public QueueAdmissionScheduler(QueueAdmissionService admissionService, @Value("${coupon.queue.admission.tick}") Duration tick) {
        this.admissionService = admissionService;
        this.tick = tick;
    }

    @PostConstruct
    void start() {
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("queue-admission-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        scheduler.scheduleWithFixedDelay(admissionService::tick, tick);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdown();
    }
}

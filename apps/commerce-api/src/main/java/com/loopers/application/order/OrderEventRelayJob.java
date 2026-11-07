package com.loopers.application.order;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Outbox relay 주기 실행. outbox.relay.enabled 가 true 인 인스턴스에서만 만든다.
 * 전용 스레드 하나에서 fixed delay 로 돈다: 회차가 겹치지 않고, 확인 대기(최대 sendTimeout)가 결제 만료 job 같은 다른 @Scheduled 를 막지 않는다.
 * 스케줄러를 빈으로 두지 않는다 (PaymentLookupScheduler 와 같은 이유).
 */
@Slf4j
@ConditionalOnProperty(name = "outbox.relay.enabled", havingValue = "true")
@Component
public class OrderEventRelayJob {
    private final OrderEventRelay relay;
    private final OutboxRelayProperties properties;
    private final ThreadPoolTaskScheduler taskScheduler;

    public OrderEventRelayJob(OrderEventRelay relay, OutboxRelayProperties properties) {
        this.relay = relay;
        this.properties = properties;
        this.taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(1);
        taskScheduler.setThreadNamePrefix("order-event-relay-");
        taskScheduler.initialize();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        taskScheduler.scheduleWithFixedDelay(this::runSafely, properties.interval());
        log.info("OrderEvent relay 시작 [interval = {}, batchSize = {}, sendTimeout = {}, maxFailures = {}]",
            properties.interval(), properties.batchSize(), properties.sendTimeout(), properties.maxFailures());
    }

    private void runSafely() {
        try {
            relay.runDue();
        } catch (RuntimeException e) {
            log.error("OrderEvent relay 회차 실패, 다음 회차에 다시 고른다", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        taskScheduler.shutdown();
    }
}

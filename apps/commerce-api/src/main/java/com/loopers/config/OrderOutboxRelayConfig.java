package com.loopers.config;

import com.loopers.application.order.OrderOutboxExecutionGate;
import com.loopers.application.order.OrderOutboxRelay;
import com.loopers.application.order.OrderOutboxRelayProbe;
import com.loopers.application.order.OrderOutboxRetryPolicy;
import com.loopers.interfaces.scheduler.OrderOutboxRelayScheduler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.TaskUtils;

import java.time.Duration;

/**
 * Outbox relay 의 policy·공유 gate·기본 probe·전용 scheduler·relay 진입 lifecycle 조립(설계 W7.3-2·W7.3-5). Clock 은 기존 outboxRecordClock 을 쓴다.
 *
 * <p>종료 순서: ContextClosedEvent 에서 전용 pool 이 early shutdown(새 주기·지연 실행 중단) → lifecycle phase 0 의 relay 진입 제어가
 * gate 를 닫고 진행 작업 finally 를 기다림 → pool 의 phase -100 lifecycle(그 사이 executor 가 이미 종료됐으면 Spring 이 stop 을 생략하고,
 * 작업이 남아 running 이면 그 종료를 기다림) → 전용 producer factory 의 MIN_VALUE 정지·physical close.
 *
 * <p>전용 pool 은 autowireCandidate=false 로 둔다. Executor 타입 bean 이 생기면 Boot 의 applicationTaskExecutor 자동 구성이 빠지므로,
 * 다른 기능의 공통 실행기를 바꾸지 않으려는 선택이다. pool 은 여전히 context bean 이라 lifecycle·ContextClosedEvent 에 참여한다.
 */
@Configuration
public class OrderOutboxRelayConfig {

    public static final String RELAY_TASK_SCHEDULER = "orderOutboxRelayTaskScheduler";
    public static final int RELAY_TASK_SCHEDULER_PHASE = -100;

    @Bean
    OrderOutboxRetryPolicy orderOutboxRetryPolicy(OrderOutboxRelayProperties properties) {
        return new OrderOutboxRetryPolicy(properties.initialRetryDelay(), properties.maxRetryDelay(), properties.maxCycleAttempts());
    }

    @Bean
    OrderOutboxExecutionGate orderOutboxExecutionGate() {
        return new OrderOutboxExecutionGate();
    }

    @Bean
    OrderOutboxRelayProbe orderOutboxRelayProbe() {
        return OrderOutboxRelayProbe.NO_OP;
    }

    /** 전용 단일 스레드. 종료 뒤 주기·지연 작업을 새로 실행하지 않고, 진행 작업은 lifecycle 정지 절차에서 끝을 확인한다. */
    @Bean(name = RELAY_TASK_SCHEDULER, autowireCandidate = false)
    ThreadPoolTaskScheduler orderOutboxRelayTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("order-outbox-relay-");
        scheduler.setAcceptTasksAfterContextClose(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setPhase(RELAY_TASK_SCHEDULER_PHASE);
        // 회차가 잡지 않은 Error 를 정상 처리로 삼키지 않는다(주기 작업은 그 뒤 멈춘다).
        scheduler.setErrorHandler(TaskUtils.LOG_AND_PROPAGATE_ERROR_HANDLER);
        return scheduler;
    }

    /** enabled 와 무관하게 등록한다. false 는 자동 회차만 끄며 종료 때 gate 정지·진행 작업 정리는 그대로다. */
    @Bean
    OrderOutboxRelayScheduler orderOutboxRelayScheduler(OrderOutboxRelay relay, OrderOutboxExecutionGate gate,
                                                        OrderOutboxRelayProperties properties,
                                                        @Value("${spring.lifecycle.timeout-per-shutdown-phase:30s}") String phaseTimeout) {
        // 진입 제어 정지의 대기 한도는 기존 lifecycle phase 대기와 같다. Boot 와 같은 형식으로 해석한다.
        Duration stopTimeout = DurationStyle.detectAndParse(phaseTimeout);
        return new OrderOutboxRelayScheduler(relay, gate, orderOutboxRelayTaskScheduler(), properties.enabled(), properties.fixedDelay(),
            stopTimeout);
    }
}

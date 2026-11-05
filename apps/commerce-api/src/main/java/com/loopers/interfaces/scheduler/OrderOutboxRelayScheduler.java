package com.loopers.interfaces.scheduler;

import com.loopers.application.order.OrderOutboxExecutionGate;
import com.loopers.application.order.OrderOutboxExecutionGate.Outcome;
import com.loopers.application.order.OrderOutboxExecutionGate.Result;
import com.loopers.application.order.OrderOutboxRelay;
import com.loopers.application.order.OrderOutboxRelay.RowResult;
import com.loopers.application.order.OrderOutboxRelayException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * Outbox relay 의 자동 회차와 relay 진입 제어 lifecycle(설계 W7.3-1·W7.3-5). phase 0 에서 시작·정지한다.
 *
 * <p>enabled 는 자동 회차만 정한다. true 면 전용 단일 스레드 scheduler 에 fixedDelay(회차 종료 뒤 지연)로 runDueBatch 를 건다.
 * false 여도 이 lifecycle 은 등록되어, context 종료 때 공유 gate 를 닫고 진행 중인 수동 run/resume/recover 를 interrupt 한 뒤
 * finally 해제를 기다린다. 대기는 lifecycle phase 대기 한도 안에서 하며, 끝나지 않으면 미완료로 기록하고 완료로 보고하지 않는다.
 *
 * <p>회차의 RuntimeException 은 안전 로그(단계·row PK·원인 클래스)만 남기고 다음 회차로 넘긴다. Error 는 잡지 않는다.
 */
@Slf4j
public class OrderOutboxRelayScheduler implements SmartLifecycle {

    public static final int PHASE = 0;

    private final OrderOutboxRelay relay;
    private final OrderOutboxExecutionGate gate;
    private final TaskScheduler taskScheduler;
    private final boolean enabled;
    private final Duration fixedDelay;
    private final Duration stopTimeout;
    private final Object monitor = new Object();
    private ScheduledFuture<?> rounds;
    private volatile boolean running;

    public OrderOutboxRelayScheduler(OrderOutboxRelay relay, OrderOutboxExecutionGate gate, TaskScheduler taskScheduler,
                                     boolean enabled, Duration fixedDelay, Duration stopTimeout) {
        this.relay = relay;
        this.gate = gate;
        this.taskScheduler = taskScheduler;
        this.enabled = enabled;
        this.fixedDelay = fixedDelay;
        this.stopTimeout = stopTimeout;
    }

    @Override
    public void start() {
        synchronized (monitor) {
            if (running) {
                return;
            }
            running = true;
            if (enabled) {
                rounds = taskScheduler.scheduleWithFixedDelay(this::runRound, fixedDelay);
            }
        }
        log.info("ORDER_OUTBOX_RELAY_SCHEDULER_STARTED enabled={} fixedDelayMs={}", enabled, fixedDelay.toMillis());
    }

    /** 자동 회차 한 번. gate 가 바쁘거나 멈췄으면 DB 변경 없이 다음 회차로 넘긴다. */
    void runRound() {
        try {
            Result<List<RowResult>> result = relay.runDueBatch();
            if (result.outcome() != Outcome.EXECUTED) {
                log.info("ORDER_OUTBOX_SCHEDULED_ROUND outcome={}", result.outcome());
            }
        } catch (RuntimeException failure) {
            // 원인 message 에는 SQL·원문이 섞일 수 있어 relay 의 안전 메시지만 남긴다.
            String detail = failure instanceof OrderOutboxRelayException ? failure.getMessage() : "none";
            log.error("ORDER_OUTBOX_SCHEDULED_ROUND_FAILED causeType={} detail={}", failure.getClass().getName(), detail);
        }
    }

    /**
     * 주기 작업을 취소하고(중복 호출 안전) gate 를 닫아 진행 thread 에 interrupt 를 전달한 뒤 finally 해제를 기다린다.
     * 전용 pool 은 이보다 앞선 ContextClosedEvent 에서 새 주기 실행을 이미 멈췄다. 이 대기 중 executor 가 종료되면 phase -100 stop 은
     * 생략될 수 있고, 아직 running 이면 phase -100 에서 남은 작업의 종료를 기다린다.
     */
    @Override
    public void stop() {
        synchronized (monitor) {
            if (rounds != null) {
                rounds.cancel(false);
            }
        }
        gate.stop();
        boolean idle;
        try {
            idle = gate.awaitIdle(stopTimeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            idle = false;
        }
        running = false;
        if (idle) {
            log.info("ORDER_OUTBOX_RELAY_STOPPED gateIdle=true");
        } else {
            log.error("ORDER_OUTBOX_RELAY_STOP_INCOMPLETE gateIdle=false waitedMs={}", stopTimeout.toMillis());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}

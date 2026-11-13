package com.loopers.interfaces.scheduler;

import com.loopers.application.coupon.CouponFacade;
import com.loopers.config.CouponWaitingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 쿠폰 대기실 입장 처리 회차. 직전 회차가 끝난 뒤 delay 를 두고 processAdmissions 를 부른다.
 * W7 relay 실행기와 공유하지 않으려고 단일 스레드 실행기를 직접 소유한다.
 * Error 는 다시 던져 이후 회차가 멈춘 사실을 숨기지 않는다.
 */
@Slf4j
@Component
public class CouponWaitingScheduler implements SmartLifecycle {

    private static final AtomicInteger THREADS = new AtomicInteger();

    private final CouponFacade couponFacade;
    private final Long campaignId;
    private final Duration delay;
    private final Duration stopTimeout;
    private final Object monitor = new Object();
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> rounds;
    private volatile boolean running;

    public CouponWaitingScheduler(CouponFacade couponFacade, CouponWaitingProperties properties,
                                  @Value("${spring.lifecycle.timeout-per-shutdown-phase:30s}") String phaseTimeout) {
        this.couponFacade = couponFacade;
        this.campaignId = properties.campaignId();
        this.delay = properties.schedulerDelay();
        this.stopTimeout = DurationStyle.detectAndParse(phaseTimeout);
    }

    @Override
    public void start() {
        synchronized (monitor) {
            if (running) {
                return;
            }
            running = true;
            if (campaignId == null) {
                log.info("COUPON_WAITING_SCHEDULER_STARTED campaignId=none(대기실 비활성, 회차 없음)");
                return;
            }
            executor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "coupon-waiting-" + THREADS.incrementAndGet()));
            long delayMillis = delay.toMillis();
            rounds = executor.scheduleWithFixedDelay(this::runRound, delayMillis, delayMillis, TimeUnit.MILLISECONDS);
        }
        log.info("COUPON_WAITING_SCHEDULER_STARTED campaignId={} delayMs={}", campaignId, delay.toMillis());
    }

    void runRound() {
        try {
            couponFacade.processAdmissions();
        } catch (RuntimeException failure) {
            log.error("COUPON_WAITING_ROUND_FAILED causeType={}", failure.getClass().getName(), failure);
        } catch (Error error) {
            log.error("COUPON_WAITING_ROUND_ERROR 이후 회차가 멈춘다(자동 재시작 없음) causeType={}", error.getClass().getName(), error);
            throw error;
        }
    }

    /** 한도 안에 진행 회차가 끝나지 않으면 정상 종료로 보고하지 않는다. */
    @Override
    public void stop() {
        ScheduledExecutorService owned;
        synchronized (monitor) {
            if (!running) {
                return;
            }
            running = false;
            if (rounds != null) {
                rounds.cancel(false);
            }
            owned = executor;
            executor = null;
            rounds = null;
        }
        if (owned == null) {
            log.info("COUPON_WAITING_SCHEDULER_STOPPED executor=none");
            return;
        }
        owned.shutdown();
        try {
            if (owned.awaitTermination(stopTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.info("COUPON_WAITING_SCHEDULER_STOPPED");
            } else {
                log.error("COUPON_WAITING_SCHEDULER_STOP_INCOMPLETE 진행 회차가 끝나지 않음 waitedMs={}", stopTimeout.toMillis());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.error("COUPON_WAITING_SCHEDULER_STOP_INTERRUPTED 진행 회차 종료 미확인");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}

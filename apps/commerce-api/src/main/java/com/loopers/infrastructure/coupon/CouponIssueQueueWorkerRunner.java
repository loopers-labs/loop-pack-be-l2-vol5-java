package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.CouponIssueAdmissionState;
import com.loopers.application.coupon.CouponIssueQueueProperties;
import com.loopers.application.coupon.CouponIssueQueueWorkResult;
import com.loopers.application.coupon.CouponIssueQueueWorker;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 쿠폰 발급 대기열 worker를 전용 스레드로 실행한다. seed 준비(0)와 수용 시작(100) 뒤에 시작하며,
 * 설정한 스레드 수만큼만 실행해 Java 안에 작업을 쌓지 않는다.
 * 종료할 때는 새 작업 가져오기를 먼저 멈추고 실행 중인 처리를 정해진 시간까지 기다린다. 끝나지 않은 작업과 token은 지우지 않는다.
 * 이 대기는 Redis 연결 같은 lifecycle 자원이 멈추기 전인 컨텍스트 종료 이벤트에서 한다. 빈 소멸 단계에서는 이미 연결이 멈춰
 * 처리를 마친 실행도 permit 반환과 결과 기록을 하지 못한다.
 */
@Slf4j
@Component
@Order(200)
public class CouponIssueQueueWorkerRunner implements ApplicationRunner, ApplicationListener<ContextClosedEvent> {

    private final CouponIssueQueueWorker worker;
    private final CouponIssueAdmissionState admission;
    private final CouponIssueQueueProperties.Worker settings;
    private final AtomicBoolean running = new AtomicBoolean();
    private final CountDownLatch stopSignal = new CountDownLatch(1);
    private ExecutorService threads;

    public CouponIssueQueueWorkerRunner(CouponIssueQueueWorker worker, CouponIssueAdmissionState admission,
                                        CouponIssueQueueProperties properties) {
        this.worker = worker;
        this.admission = admission;
        this.settings = properties.worker();
    }

    @Override
    public void run(ApplicationArguments args) {
        start();
    }

    synchronized void start() {
        if (!settings.enabled()) {
            log.info("쿠폰 발급 대기열 worker 자동 실행이 꺼져 있습니다. 접수한 작업은 worker를 켤 때까지 대기합니다.");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        AtomicInteger sequence = new AtomicInteger();
        threads = Executors.newFixedThreadPool(settings.concurrency(),
            task -> new Thread(task, "coupon-queue-worker-" + sequence.incrementAndGet()));
        for (int index = 0; index < settings.concurrency(); index++) {
            threads.execute(this::loop);
        }
        log.info("쿠폰 발급 대기열 worker를 시작했습니다. concurrency={}, pollInterval={}",
            settings.concurrency(), settings.pollInterval());
    }

    private void loop() {
        while (running.get()) {
            CouponIssueQueueWorkResult result;
            try {
                result = worker.runOnce();
            } catch (RuntimeException | Error exception) {
                // 처리 중이던 작업과 token의 상태를 알 수 없으므로 수동 확인 전까지 신규 수용을 닫는다.
                // 작업은 처리 중 List에 남아 수동 복구 대상이 되고, 스레드는 다음 주기에 다시 확인한다.
                admission.close();
                log.error("쿠폰 발급 대기열 worker 처리 중 예상하지 못한 오류가 발생해 신규 발급을 닫았습니다.", exception);
                result = CouponIssueQueueWorkResult.STOPPED;
            }
            if (result == CouponIssueQueueWorkResult.PROCESSED) {
                continue;
            }
            if (waitForNextPoll()) {
                return;
            }
        }
    }

    /**
     * 확인 주기만큼 기다린다. 종료 신호가 오면 바로 깨어나 true를 돌려준다.
     */
    private boolean waitForNextPoll() {
        try {
            return stopSignal.await(settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        stop();
    }

    @PreDestroy
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        stopSignal.countDown();
        threads.shutdown();
        try {
            if (!threads.awaitTermination(settings.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("종료 대기 시간 안에 끝나지 않은 쿠폰 발급 처리가 있습니다. 작업과 실행 token은 지우지 않으며, "
                    + "처리 중 작업은 수동으로 확인해야 합니다. shutdownTimeout={}", settings.shutdownTimeout());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}

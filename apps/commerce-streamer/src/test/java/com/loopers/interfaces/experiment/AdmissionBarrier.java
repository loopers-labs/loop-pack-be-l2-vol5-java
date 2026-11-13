package com.loopers.interfaces.experiment;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 세마포어 실험의 barrier. 자리를 잡은 직후 · DB TX 시작 전에 실행을 붙잡는다 (과제 5장, 8주차 D-157).
 * 운영 코드에 대기 지점을 두지 않도록 테스트 소스에만 둔다. 대기에는 deadline 이 있다
 */
@Component
public class AdmissionBarrier {

    private static final Duration DEADLINE = Duration.ofSeconds(15);

    private volatile boolean holding;
    private volatile int expected;
    private final AtomicInteger arrived = new AtomicInteger();
    private final Semaphore passes = new Semaphore(0);
    private volatile CountDownLatch arrivals = new CountDownLatch(0);

    /** 이후 도착하는 실행을 처음 expected 개까지만 붙잡는다. 그 뒤 도착은 그대로 지나간다 */
    public void hold(int expected) {
        this.expected = expected;
        holding = true;
        arrived.set(0);
        passes.drainPermits();
        arrivals = new CountDownLatch(expected);
    }

    public boolean awaitArrivals() throws InterruptedException {
        return arrivals.await(DEADLINE.toSeconds(), TimeUnit.SECONDS);
    }

    /** 붙잡힌 실행 하나를 보낸다 */
    public void passOne() {
        passes.release();
    }

    /** 남은 실행을 모두 보내고 더 붙잡지 않는다. 실패한 테스트도 자기가 시작한 실행을 풀고 끝낸다 */
    public void releaseAll() {
        holding = false;
        passes.release(1_000);
    }

    void arrive() throws InterruptedException {
        if (!holding || arrived.incrementAndGet() > expected) {
            return;
        }
        arrivals.countDown();
        if (!passes.tryAcquire(DEADLINE.toSeconds(), TimeUnit.SECONDS)) {
            throw new IllegalStateException("barrier deadline 을 넘김");
        }
    }
}

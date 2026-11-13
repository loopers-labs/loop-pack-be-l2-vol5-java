package com.loopers.application.coupon;

import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * Redis를 쓸 수 없을 때 신규 발급을 DB로 곧장 보내는 직행 경로의 동시 실행 상한(JVM 세마포어, C와 같게).
 * 정상 경로(Redis 세마포어)에서는 쓰지 않는다. 기다리지 않고 바로 실패하며, 진행 중 수를 센다.
 */
@Component
public class LocalLimiter {

    private final int capacity;
    private final Semaphore semaphore;

    public LocalLimiter(CouponAdmissionProperties properties) {
        this.capacity = properties.capacity();
        this.semaphore = new Semaphore(capacity);
    }

    /** 기다리지 않고 자리를 얻는다. 얻었으면 끝난 뒤 {@link #release()}로 반환해야 한다. */
    public boolean tryAcquire() {
        return semaphore.tryAcquire();
    }

    public void release() {
        semaphore.release();
    }

    /** 직행으로 진행 중인 실행 수. */
    public int inProgress() {
        return capacity - semaphore.availablePermits();
    }
}

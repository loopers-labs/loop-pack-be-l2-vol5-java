package com.loopers.infrastructure.concurrency.coupon;

import com.loopers.application.coupon.port.CouponAdmission;
import com.loopers.config.coupon.CouponAdmissionProperties;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class SemaphoreCouponAdmission implements CouponAdmission {
    private final int capacity;
    private final Semaphore semaphore;

    public SemaphoreCouponAdmission(CouponAdmissionProperties properties) {
        capacity = properties.maxConcurrency();
        semaphore = new Semaphore(capacity);
    }

    @Override
    public Optional<LocalPermit> tryAcquire() {
        if (!semaphore.tryAcquire()) {
            return Optional.empty();
        }
        AtomicBoolean returned = new AtomicBoolean();
        return Optional.of(() -> {
            if (!returned.compareAndSet(false, true)) {
                return false;
            }
            semaphore.release();
            return true;
        });
    }

    @Override public int capacity() { return capacity; }
    @Override public int available() { return semaphore.availablePermits(); }
}

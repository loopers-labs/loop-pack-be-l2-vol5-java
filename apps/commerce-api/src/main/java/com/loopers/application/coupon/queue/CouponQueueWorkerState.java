package com.loopers.application.coupon.queue;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** A local gate; reopening requires a new, explicitly enabled instance after reconciliation. */
@Component
public class CouponQueueWorkerState {
    private final AtomicBoolean open;
    private final Object admissionLock = new Object();

    @Autowired
    public CouponQueueWorkerState(Environment environment,
                                  @Value("${coupon.queue.open-on-start:false}") boolean openOnStart) {
        this(openOnStart && localOrTestOnly(environment.getActiveProfiles()));
    }

    public CouponQueueWorkerState(boolean openOnStart) {
        this.open = new AtomicBoolean(openOnStart);
    }

    public boolean acceptingNewRequests() {
        return open.get();
    }

    public boolean processingAllowed() {
        return open.get();
    }

    /** Serialize a Redis admission decision with pause; pause is reentrant on failure. */
    public <T> T withAdmissionLock(Supplier<T> action) {
        synchronized (admissionLock) {
            return action.get();
        }
    }

    public void pause() {
        // Close immediately, including to admissions waiting for the monitor.
        // An admission already inside may have sent its Redis command; wait
        // until it has finished before reporting that pause has completed.
        open.set(false);
        synchronized (admissionLock) {
            open.set(false);
        }
    }

    private static boolean localOrTestOnly(String[] profiles) {
        return profiles.length > 0
                && Arrays.stream(profiles).allMatch(profile -> profile.equals("local") || profile.equals("test"));
    }
}

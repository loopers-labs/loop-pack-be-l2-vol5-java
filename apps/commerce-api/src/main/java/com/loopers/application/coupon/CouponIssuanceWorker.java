package com.loopers.application.coupon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Single dispatcher for the configured campaigns. The shared semaphore covers the entire issuance transaction. */
public class CouponIssuanceWorker {
    private static final Logger log = LoggerFactory.getLogger(CouponIssuanceWorker.class);
    private final CouponIssuanceService issuance;
    private final CouponIssueSemaphore slots;
    private final Executor executor;
    private final int capacity;
    private final Set<Long> running = new HashSet<>();
    private final Set<Attempt> unresolved = new HashSet<>();

    public CouponIssuanceWorker(CouponIssuanceService issuance, CouponIssueSemaphore slots, Executor executor, int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Issuance worker capacity must be positive");
        }
        this.issuance = issuance;
        this.slots = slots;
        this.executor = executor;
        this.capacity = capacity;
    }

    public synchronized void open() {
        requireOutsideTransaction();
        slots.close();
        if (!running.isEmpty()) {
            throw new IllegalStateException("Wait for running coupon issuance tasks to finish before reopening");
        }
        Iterator<Attempt> pending = unresolved.iterator();
        while (pending.hasNext()) {
            Attempt attempt = pending.next();
            verifySettlement(attempt);
            release(attempt);
            pending.remove();
        }
        slots.open();
    }

    public synchronized void close() {
        slots.close();
    }

    public synchronized void poll(List<Long> campaignIds) {
        requireOutsideTransaction();
        if (!slots.isOpen() || running.size() >= capacity) {
            return;
        }
        for (var reservation : issuance.findWaiting(campaignIds, capacity)) {
            if (!running.contains(reservation.reservationId()) && !dispatch(reservation)) {
                break;
            }
            if (running.size() >= capacity) {
                break;
            }
        }
    }

    private boolean dispatch(CouponReservationInfo reservation) {
        var attempt = new Attempt(reservation, UUID.randomUUID());
        log.info("Acquiring coupon issuance token={} campaign={} reservation={}",
            attempt.token(), reservation.campaignId(), reservation.reservationId());
        try {
            if (!slots.tryAcquire(attempt.token())) {
                return false;
            }
        } catch (CouponIssueSemaphoreUnavailableException failure) {
            slots.close();
            log.warn("Acquisition unconfirmed for coupon reservation={} token={}",
                reservation.reservationId(), attempt.token(), failure);
            return false;
        }

        log.info("Acquired coupon issuance token={} campaign={} reservation={} user={}",
            attempt.token(), reservation.campaignId(), reservation.reservationId(), reservation.userId());
        running.add(reservation.reservationId());
        try {
            executor.execute(() -> execute(attempt));
            return true;
        } catch (RejectedExecutionException failure) {
            running.remove(reservation.reservationId());
            release(attempt);
            log.warn("Dispatch rejected for coupon reservation={} token={}",
                reservation.reservationId(), attempt.token(), failure);
            return false;
        }
    }

    private void execute(Attempt attempt) {
        var reservation = attempt.reservation();
        boolean settled = false;
        try {
            issuance.issue(reservation.campaignId(), reservation.userId());
            settled = true;
        } catch (RuntimeException failure) {
            log.warn("Checking outcome of coupon issuance reservation={} token={}",
                reservation.reservationId(), attempt.token(), failure);
            try {
                verifySettlement(attempt);
                settled = true;
            } catch (RuntimeException verificationFailure) {
                log.error("Coupon issuance could not be verified for reservation={} token={}",
                    reservation.reservationId(), attempt.token(), verificationFailure);
            }
        } finally {
            finish(attempt, settled);
        }
    }

    private synchronized void finish(Attempt attempt, boolean settled) {
        if (settled) {
            release(attempt);
        } else {
            unresolved.add(attempt);
            slots.close();
        }
        running.remove(attempt.reservation().reservationId());
    }

    private void verifySettlement(Attempt attempt) {
        var expected = attempt.reservation();
        var current = issuance.verifyIssuance(expected.campaignId(), expected.userId());
        if (expected.reservationId() != current.reservationId()) {
            throw new IllegalStateException("Reservation identity changed while reconciling coupon issuance token " + attempt.token());
        }
        log.info("Verified coupon reservation={} status={} token={}", current.reservationId(), current.status(), attempt.token());
    }

    private void release(Attempt attempt) {
        try {
            slots.release(attempt.token());
        } catch (CouponIssueSemaphoreUnavailableException failure) {
            // This task has finished. The adapter retains its token for explicit reconciliation.
            slots.close();
            log.warn("Release unconfirmed for coupon reservation={} token={}",
                attempt.reservation().reservationId(), attempt.token(), failure);
        }
    }

    private static void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Coupon worker coordination requires an independent issuance transaction");
        }
    }

    private record Attempt(CouponReservationInfo reservation, UUID token) { }
}

package com.loopers.application.coupon.queue;

import java.util.Optional;

/** The queue limit counts waiting requests; claimed requests are tracked separately. */
public interface CouponRequestQueue {
    enum SubmitOutcome {
        ACCEPTED, EXISTING, FULL, PAUSED, SOLD_OUT
    }

    record SubmitResult(SubmitOutcome outcome, String requestId) {
    }

    record Claim(String requestId, long campaignId, long userId, String workerId,
                 long retryDelayMillis, boolean retryExhausted) {
        public Claim(String requestId, long campaignId, long userId, String workerId) {
            this(requestId, campaignId, userId, workerId, 0, false);
        }
    }

    /** A positive delay means no attempt was reserved; zero means the next attempt was reserved. */
    record AttemptReservation(boolean exhausted, long delayMillis, int attemptCount) {
        public AttemptReservation(boolean exhausted, long delayMillis) {
            this(exhausted, delayMillis, 0);
        }
    }

    record RetrySchedule(boolean exhausted, long delayMillis) {
    }

    SubmitResult submit(long campaignId, long userId);

    /** Stop new submissions after the database has confirmed this campaign is sold out. */
    void markSoldOut(long campaignId);

    Optional<Claim> claim(String workerId);

    /** Forget a completed claim and release its deduplication entry atomically. */
    void complete(Claim claim);

    /** Persist an attempt before DB access; an unknown result must stop the worker. */
    AttemptReservation reserveAttempt(Claim claim);

    /** Record the next delay only after the write transaction is known to have rolled back. */
    RetrySchedule scheduleRetry(Claim claim);

    /** Returns false without discarding the claim when the waiting queue is full. */
    boolean retry(Claim claim);

}

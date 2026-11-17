package com.loopers.application.coupon;

import java.util.UUID;

public interface CouponIssueSemaphore {
    /** Explicit reconciliation after executions finish and uncertain DB outcomes have been checked. */
    void open();

    void close();

    boolean isOpen();

    /** False means all slots were occupied at the master. An unknown result throws. */
    boolean tryAcquire(UUID executionToken);

    /** Called only after this execution and its database transaction have ended. */
    void release(UUID executionToken);
}

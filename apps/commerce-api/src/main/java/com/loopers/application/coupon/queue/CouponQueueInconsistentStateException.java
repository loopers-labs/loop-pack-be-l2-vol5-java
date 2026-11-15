package com.loopers.application.coupon.queue;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

/** Redis confirmed that the request or its processing owner no longer matches. */
public class CouponQueueInconsistentStateException extends CoreException {
    public CouponQueueInconsistentStateException() {
        super(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
    }
}

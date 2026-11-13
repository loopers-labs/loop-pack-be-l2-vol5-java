package com.loopers.interfaces.consumer;

public final class UnreadableCouponIssue extends RuntimeException {

    UnreadableCouponIssue(Throwable cause) {
        super(cause.getMessage(), cause);
    }
}

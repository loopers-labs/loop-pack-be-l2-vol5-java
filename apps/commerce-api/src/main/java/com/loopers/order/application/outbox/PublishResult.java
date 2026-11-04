package com.loopers.order.application.outbox;

public record PublishResult(Status status, String errorType) {

    public static final PublishResult SUCCESS = new PublishResult(Status.SUCCESS, null);
    public static final PublishResult FAILED = new PublishResult(Status.FAILED, null);
    public static final PublishResult PENDING = new PublishResult(Status.PENDING, null);

    public static PublishResult failed(String errorType) {
        return new PublishResult(Status.FAILED, errorType);
    }

    public static PublishResult pending(String errorType) {
        return new PublishResult(Status.PENDING, errorType);
    }

    public enum Status {
        SUCCESS,
        FAILED,
        PENDING
    }
}

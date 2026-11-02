package com.loopers.purchaseactivity.domain;

public class DuplicateConfirmationException extends RuntimeException {
    public DuplicateConfirmationException(Throwable cause) {
        super("A confirmation with this eventId or orderId was committed concurrently", cause);
    }
}

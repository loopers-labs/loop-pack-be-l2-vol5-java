package com.loopers.domain.order;

public class OrderException extends RuntimeException {
    public enum Reason {
        ORDER_NOT_FOUND,
        AMOUNT_LIMIT_EXCEEDED,
        INVALID_ITEMS
    }

    private final Reason reason;

    public OrderException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}

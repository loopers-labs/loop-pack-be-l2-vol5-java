package com.loopers.domain.order;

public class OrderQuantityException extends RuntimeException {

    public enum Reason {
        INVALID_ITEMS,
        INVALID_PRODUCT_ID,
        INVALID_QUANTITY,
        QUANTITY_LIMIT_EXCEEDED
    }

    private final Reason reason;

    OrderQuantityException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}

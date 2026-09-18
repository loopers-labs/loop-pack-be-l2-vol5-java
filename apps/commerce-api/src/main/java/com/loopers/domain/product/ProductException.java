package com.loopers.domain.product;

public class ProductException extends RuntimeException {

    public enum Reason {
        INVALID_NAME,
        INVALID_PRICE,
        DELETED_PRODUCT
    }

    private final Reason reason;

    public ProductException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}

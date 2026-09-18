package com.loopers.application.product;

public class ProductQueryException extends RuntimeException {

    public enum Reason {
        BRAND_NOT_FOUND,
        PRODUCT_NOT_FOUND
    }

    private final Reason reason;

    public ProductQueryException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}

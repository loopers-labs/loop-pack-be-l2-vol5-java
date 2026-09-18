package com.loopers.application.brand;

public class BrandQueryException extends RuntimeException {

    public enum Reason {
        BRAND_NOT_FOUND
    }

    private final Reason reason;

    BrandQueryException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}

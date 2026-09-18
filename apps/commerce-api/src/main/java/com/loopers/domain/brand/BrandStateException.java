package com.loopers.domain.brand;

public class BrandStateException extends RuntimeException {

    public enum Reason {
        DELETED_BRAND
    }

    private final Reason reason;

    BrandStateException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}

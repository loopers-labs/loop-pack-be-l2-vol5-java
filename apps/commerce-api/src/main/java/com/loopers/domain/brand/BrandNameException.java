package com.loopers.domain.brand;

public class BrandNameException extends RuntimeException {

    public enum Reason {
        EMPTY_NAME,
        NAME_TOO_LONG
    }

    private final Reason reason;

    BrandNameException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}

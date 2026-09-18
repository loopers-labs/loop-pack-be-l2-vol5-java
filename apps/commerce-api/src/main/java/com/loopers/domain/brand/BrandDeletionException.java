package com.loopers.domain.brand;

public class BrandDeletionException extends RuntimeException {

    public enum Reason {
        BRAND_NOT_FOUND,
        NON_DELETED_PRODUCTS_EXIST
    }

    private final Reason reason;

    BrandDeletionException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}

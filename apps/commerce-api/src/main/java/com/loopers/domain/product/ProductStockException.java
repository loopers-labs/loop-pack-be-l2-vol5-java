package com.loopers.domain.product;

public class ProductStockException extends RuntimeException {

    public enum Reason {
        INVALID_STOCK_QUANTITY,
        INVALID_DEDUCTION_QUANTITY,
        INSUFFICIENT_STOCK
    }

    private final Reason reason;

    ProductStockException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}

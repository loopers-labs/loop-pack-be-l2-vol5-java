package com.loopers.domain.purchase;

public class PurchaseActivityConflictException extends RuntimeException {
    public PurchaseActivityConflictException() {
        super("A confirmation identity refers to a different stored purchase fact");
    }
}

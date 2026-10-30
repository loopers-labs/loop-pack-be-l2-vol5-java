package com.loopers.domain.purchase;

/** A completion identity collided; the failed transaction must roll back before checking the stored fact. */
public class ConfirmationAlreadyRecordedException extends RuntimeException {
    public ConfirmationAlreadyRecordedException(Throwable cause) {
        super("A confirmation identity is already recorded", cause);
    }
}

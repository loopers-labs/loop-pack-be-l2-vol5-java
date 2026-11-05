package com.loopers.application.purchase.port;

public class DuplicateConfirmationException extends RuntimeException {

    public DuplicateConfirmationException(String message, Throwable cause) {
        super(message, cause);
    }
}

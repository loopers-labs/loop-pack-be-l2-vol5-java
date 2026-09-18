package com.loopers.support.error;

public class DomainException extends RuntimeException {

    private final DomainError error;

    public DomainException(DomainError error) {
        this(error, error.message());
    }

    public DomainException(DomainError error, String message) {
        super(message, null, false, false);
        this.error = error;
    }

    public DomainError error() {
        return error;
    }

    public Failure failure() {
        return error.failure();
    }

    public String code() {
        return error.code();
    }
}

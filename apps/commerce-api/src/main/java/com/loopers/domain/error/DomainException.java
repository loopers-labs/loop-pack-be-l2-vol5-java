package com.loopers.domain.error;

import lombok.Getter;

@Getter
public class DomainException extends RuntimeException {
    private final DomainErrorType type;

    public DomainException(DomainErrorType type, String message) {
        super(message);
        this.type = type;
    }
}

package com.loopers.support.error;

import lombok.Getter;

@Getter
public class CoreException extends RuntimeException {
    private final ErrorCode errorCode;
    private final String customMessage;

    public CoreException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    public CoreException(ErrorCode errorCode, String customMessage) {
        super(customMessage != null ? customMessage : errorCode.getMessage());
        this.errorCode = errorCode;
        this.customMessage = customMessage;
    }
}

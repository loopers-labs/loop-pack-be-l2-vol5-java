package com.loopers.support.error;

import lombok.Getter;

@Getter
public class CoreException extends RuntimeException {
    private final ErrorCode errorCode;
    private final String customMessage;
    /** 실패 응답의 data 로 내보낼 부가 정보. 예: 문제가 된 상품 식별자. 없으면 null. */
    private final Object detail;

    public CoreException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    public CoreException(ErrorCode errorCode, String customMessage) {
        this(errorCode, customMessage, null);
    }

    public CoreException(ErrorCode errorCode, String customMessage, Object detail) {
        super(customMessage != null ? customMessage : errorCode.getMessage());
        this.errorCode = errorCode;
        this.customMessage = customMessage;
        this.detail = detail;
    }
}

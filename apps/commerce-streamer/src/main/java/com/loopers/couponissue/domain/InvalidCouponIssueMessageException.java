package com.loopers.couponissue.domain;

/**
 * 계약에 맞지 않는 발급 메시지. 재시도해도 같으므로 DLT로 간다.
 */
public class InvalidCouponIssueMessageException extends RuntimeException {

    public InvalidCouponIssueMessageException(String message) {
        super(message);
    }

    public InvalidCouponIssueMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}

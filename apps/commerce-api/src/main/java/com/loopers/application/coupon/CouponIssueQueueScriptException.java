package com.loopers.application.coupon;

/**
 * 대기열 스크립트의 입력, 키 타입 또는 저장된 작업 정보가 올바르지 않다.
 */
public class CouponIssueQueueScriptException extends RuntimeException {

    public CouponIssueQueueScriptException(String message, Throwable cause) {
        super(message, cause);
    }
}

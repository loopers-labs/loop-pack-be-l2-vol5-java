package com.loopers.domains.coupon.app.port;

// 같은 캠페인·사용자의 쿠폰이 이미 있어 INSERT가 유일 제약에 걸렸다. app이 JPA 예외를 모르도록 어댑터가 이 예외로 바꾼다.
public class DuplicateIssueException extends RuntimeException {
    public DuplicateIssueException(String message, Throwable cause) {
        super(message, cause);
    }
}

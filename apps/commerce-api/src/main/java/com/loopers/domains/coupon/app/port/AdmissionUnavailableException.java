package com.loopers.domains.coupon.app.port;

// 수용 자리·설정을 확인할 수 없다(Redis·DB 실패). app이 Redis·Spring 예외 타입을 모르도록 어댑터가 이 예외로 바꾼다(D123).
public class AdmissionUnavailableException extends RuntimeException {
    public AdmissionUnavailableException(String message) {
        super(message);
    }

    public AdmissionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

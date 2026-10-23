package com.loopers.application.payment;

/**
 * PG에 물어보지 못했다(timeout·5xx·깨진 응답, 나중에는 회로 OPEN). "거래 없음"과 다르다 —
 * 이 예외를 받은 호출자는 저장된 상태를 바꾸지 않고 "확인 불가"를 돌려준다(INV-004).
 */
public class PaymentGatewayUnavailableException extends RuntimeException {

    public PaymentGatewayUnavailableException(String message) {
        super(message);
    }

    public PaymentGatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

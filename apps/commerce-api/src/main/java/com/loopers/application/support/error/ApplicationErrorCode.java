package com.loopers.application.support.error;

// 애플리케이션 계층 에러 코드
public enum ApplicationErrorCode {
    USER_NOT_FOUND("사용자를 찾을 수 없습니다."),
    BRAND_NOT_FOUND("브랜드를 찾을 수 없습니다."),
    PRODUCT_NOT_FOUND("상품을 찾을 수 없습니다."),
    ORDER_NOT_FOUND("주문을 찾을 수 없습니다."),
    ORDER_ADMIN_FORBIDDEN("주문 관리자 권한이 필요합니다."),
    PAYMENT_ATTEMPT_NOT_FOUND("결제 시도를 찾을 수 없습니다."),
    PAYMENT_CALLBACK_CONFLICT("결제 알림의 식별 정보가 기존 거래와 일치하지 않습니다."),
    PAYMENT_REQUEST_CONFLICT("기존 결제 요청과 일치하지 않습니다.");

    private final String message;

    ApplicationErrorCode(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}

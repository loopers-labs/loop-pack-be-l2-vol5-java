package com.loopers.application.support.error;

// 애플리케이션 계층 에러 코드
public enum ApplicationErrorCode {
    USER_NOT_FOUND("사용자를 찾을 수 없습니다."),
    BRAND_NOT_FOUND("브랜드를 찾을 수 없습니다."),
    PRODUCT_NOT_FOUND("상품을 찾을 수 없습니다."),
    ORDER_NOT_FOUND("주문을 찾을 수 없습니다.");

    private final String message;

    ApplicationErrorCode(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}

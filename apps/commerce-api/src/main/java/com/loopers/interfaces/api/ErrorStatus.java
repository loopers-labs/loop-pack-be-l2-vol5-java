package com.loopers.interfaces.api;

import com.loopers.support.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 오류 코드를 API 응답 스키마의 상태 코드로 바꾼다.
 */
public final class ErrorStatus {

    private ErrorStatus() {}

    public static HttpStatus of(ErrorCode errorCode) {
        return switch (errorCode) {
            case INVALID_REQUEST,
                 INVALID_STOCK_QUANTITY,
                 INVALID_PRODUCT_NAME,
                 INVALID_PRODUCT_PRICE,
                 INVALID_BRAND_NAME,
                 INVALID_CHARGE_AMOUNT,
                 INVALID_ORDER_QUANTITY,
                 EMPTY_ORDER_ITEMS -> HttpStatus.BAD_REQUEST;
            case USER_NOT_IDENTIFIED -> HttpStatus.UNAUTHORIZED;
            case BRAND_NOT_FOUND,
                 PRODUCT_NOT_FOUND,
                 ORDER_NOT_FOUND,
                 USER_NOT_FOUND,
                 LIKE_NOT_FOUND,
                 NOT_FOUND -> HttpStatus.NOT_FOUND;
            case METHOD_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED;
            case DUPLICATE_BRAND_NAME,
                 DUPLICATE_PRODUCT_NAME,
                 BRAND_HAS_PRODUCTS,
                 ORDER_ALREADY_CONFIRMED,
                 BRAND_CHANGE_NOT_ALLOWED,
                 PRODUCT_NOT_AVAILABLE,
                 INSUFFICIENT_STOCK,
                 INSUFFICIENT_POINT,
                 POINT_BALANCE_LIMIT_EXCEEDED -> HttpStatus.CONFLICT;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}

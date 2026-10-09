package com.loopers.interfaces.api;

import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.support.error.ErrorType;
import org.springframework.stereotype.Component;

@Component
// 도메인/애플리케이션 에러 코드를 HTTP 에러 타입으로 매핑
public class ApiErrorMapper {

    // 도메인 에러 코드 매핑
    public ErrorType map(DomainErrorCode errorCode) {
        return switch (errorCode) {
            case INVALID_USER_ID, INVALID_MONEY, NON_POSITIVE_MONEY, CALCULATION_OVERFLOW,
                 INVALID_STOCK, INVALID_QUANTITY, INVALID_NAME, INVALID_DESCRIPTION,
                 EMPTY_ORDER_ITEMS -> ErrorType.BAD_REQUEST;
            case DELETED_BRAND, DELETED_PRODUCT -> ErrorType.NOT_FOUND;
            case INSUFFICIENT_STOCK, ORDER_ALREADY_CONFIRMED, INSUFFICIENT_POINT -> ErrorType.CONFLICT;
        };
    }

    // 애플리케이션 에러 코드 매핑
    public ErrorType map(ApplicationErrorCode errorCode) {
        return switch (errorCode) {
            case USER_NOT_FOUND, BRAND_NOT_FOUND, PRODUCT_NOT_FOUND, ORDER_NOT_FOUND -> ErrorType.NOT_FOUND;
        };
    }
}

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
                 EMPTY_ORDER_ITEMS, INVALID_PAYMENT_REQUEST, INVALID_PAYMENT_CALLBACK,
                 COUPON_INVALID_CAMPAIGN_NAME, COUPON_INVALID_BENEFIT,
                 COUPON_INVALID_QUOTA, COUPON_INVALID_PERIOD -> ErrorType.BAD_REQUEST;
            case DELETED_BRAND, DELETED_PRODUCT -> ErrorType.NOT_FOUND;
            case INSUFFICIENT_STOCK, ORDER_ALREADY_CONFIRMED, ORDER_PAYMENT_NOT_ALLOWED, INSUFFICIENT_POINT,
                 PAYMENT_DISPATCH_NOT_ALLOWED, PAYMENT_RECOVERY_NOT_ALLOWED,
                 STOCK_ADJUSTMENT_ALREADY_FINISHED, PAYMENT_OBSERVATION_NOT_ALLOWED,
                 COUPON_SLOTS_NOT_READY, COUPON_CAMPAIGN_NOT_STARTED, COUPON_CAMPAIGN_ENDED,
                 COUPON_CAMPAIGN_INACTIVE, COUPON_SOLD_OUT, COUPON_SLOT_ALREADY_ISSUED -> ErrorType.CONFLICT;
        };
    }

    // 애플리케이션 에러 코드 매핑
    public ErrorType map(ApplicationErrorCode errorCode) {
        return switch (errorCode) {
            case USER_NOT_FOUND, BRAND_NOT_FOUND, PRODUCT_NOT_FOUND, ORDER_NOT_FOUND,
                 PAYMENT_ATTEMPT_NOT_FOUND, COUPON_CAMPAIGN_NOT_FOUND -> ErrorType.NOT_FOUND;
            case PAYMENT_REQUEST_CONFLICT, PAYMENT_CALLBACK_CONFLICT -> ErrorType.CONFLICT;
            case ORDER_ADMIN_FORBIDDEN, COUPON_ADMIN_FORBIDDEN -> ErrorType.FORBIDDEN;
        };
    }
}

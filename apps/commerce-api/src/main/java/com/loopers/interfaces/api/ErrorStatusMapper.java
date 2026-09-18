package com.loopers.interfaces.api;

import com.loopers.domain.brand.BrandErrorCode;
import com.loopers.domain.order.OrderErrorCode;
import com.loopers.domain.point.PointErrorCode;
import com.loopers.domain.product.ProductErrorCode;
import com.loopers.domain.user.UserErrorCode;
import com.loopers.support.error.ErrorCode;
import com.loopers.support.error.ErrorType;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * 업무 오류 코드를 HTTP 상태로 바꾸는 한 곳. 대응표에 없는 코드는 500 으로 응답한다.
 */
public final class ErrorStatusMapper {

    private static final Map<ErrorCode, HttpStatus> STATUSES = Map.ofEntries(
        Map.entry(ErrorType.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR),
        Map.entry(ErrorType.BAD_REQUEST, HttpStatus.BAD_REQUEST),
        Map.entry(ErrorType.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED),
        Map.entry(ErrorType.NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(ErrorType.CONFLICT, HttpStatus.CONFLICT),
        Map.entry(BrandErrorCode.BRAND_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(BrandErrorCode.INVALID_BRAND_NAME, HttpStatus.BAD_REQUEST),
        Map.entry(BrandErrorCode.INVALID_BRAND_DESCRIPTION, HttpStatus.BAD_REQUEST),
        Map.entry(BrandErrorCode.BRAND_HAS_PRODUCTS, HttpStatus.CONFLICT),
        Map.entry(ProductErrorCode.PRODUCT_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(ProductErrorCode.INVALID_PRODUCT_NAME, HttpStatus.BAD_REQUEST),
        Map.entry(ProductErrorCode.INVALID_PRICE, HttpStatus.BAD_REQUEST),
        Map.entry(ProductErrorCode.OUT_OF_STOCK, HttpStatus.CONFLICT),
        Map.entry(ProductErrorCode.INVALID_STOCK, HttpStatus.BAD_REQUEST),
        Map.entry(UserErrorCode.USER_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(PointErrorCode.INVALID_CHARGE_AMOUNT, HttpStatus.BAD_REQUEST),
        Map.entry(PointErrorCode.BALANCE_LIMIT_EXCEEDED, HttpStatus.CONFLICT),
        Map.entry(PointErrorCode.INSUFFICIENT_POINT, HttpStatus.CONFLICT),
        Map.entry(OrderErrorCode.ORDER_NOT_FOUND, HttpStatus.NOT_FOUND),
        Map.entry(OrderErrorCode.ORDER_ALREADY_CONFIRMED, HttpStatus.CONFLICT),
        Map.entry(OrderErrorCode.EMPTY_ORDER_ITEMS, HttpStatus.BAD_REQUEST),
        Map.entry(OrderErrorCode.INVALID_QUANTITY, HttpStatus.BAD_REQUEST),
        Map.entry(OrderErrorCode.PRODUCT_PRICE_CHANGED, HttpStatus.CONFLICT)
    );

    private ErrorStatusMapper() {}

    public static HttpStatus statusOf(ErrorCode errorCode) {
        return STATUSES.getOrDefault(errorCode, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    static boolean isMapped(ErrorCode errorCode) {
        return STATUSES.containsKey(errorCode);
    }
}

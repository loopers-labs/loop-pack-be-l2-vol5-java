package com.loopers.interfaces.api;

import com.loopers.support.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorStatusTest {

    @DisplayName("오류 코드는 API 응답 스키마의 상태 코드로 바뀐다.")
    @ParameterizedTest
    @CsvSource({
        "INVALID_REQUEST, BAD_REQUEST",
        "INVALID_STOCK_QUANTITY, BAD_REQUEST",
        "INVALID_PRODUCT_NAME, BAD_REQUEST",
        "INVALID_PRODUCT_PRICE, BAD_REQUEST",
        "INVALID_BRAND_NAME, BAD_REQUEST",
        "INVALID_CHARGE_AMOUNT, BAD_REQUEST",
        "INVALID_ORDER_QUANTITY, BAD_REQUEST",
        "EMPTY_ORDER_ITEMS, BAD_REQUEST",
        "USER_NOT_IDENTIFIED, UNAUTHORIZED",
        "BRAND_NOT_FOUND, NOT_FOUND",
        "PRODUCT_NOT_FOUND, NOT_FOUND",
        "ORDER_NOT_FOUND, NOT_FOUND",
        "USER_NOT_FOUND, NOT_FOUND",
        "LIKE_NOT_FOUND, NOT_FOUND",
        "NOT_FOUND, NOT_FOUND",
        "METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED",
        "DUPLICATE_BRAND_NAME, CONFLICT",
        "DUPLICATE_PRODUCT_NAME, CONFLICT",
        "BRAND_HAS_PRODUCTS, CONFLICT",
        "ORDER_ALREADY_CONFIRMED, CONFLICT",
        "BRAND_CHANGE_NOT_ALLOWED, CONFLICT",
        "PRODUCT_NOT_AVAILABLE, CONFLICT",
        "INSUFFICIENT_STOCK, CONFLICT",
        "INSUFFICIENT_POINT, CONFLICT",
        "POINT_BALANCE_LIMIT_EXCEEDED, CONFLICT",
        "INTERNAL_ERROR, INTERNAL_SERVER_ERROR",
    })
    void mapsErrorCodeToContractStatus(ErrorCode errorCode, HttpStatus expected) {
        // act
        HttpStatus result = ErrorStatus.of(errorCode);

        // assert
        assertThat(result).isEqualTo(expected);
    }
}

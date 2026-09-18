package com.loopers.support.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorType {
    /** 범용 에러 */
    INTERNAL_ERROR("Internal Server Error", "일시적인 오류가 발생했습니다."),
    BAD_REQUEST("Bad Request", "잘못된 요청입니다."),
    NOT_FOUND("Not Found", "존재하지 않는 요청입니다."),
    CONFLICT("Conflict", "이미 존재하는 리소스입니다."),
    INVALID_REQUEST("INVALID_REQUEST", "잘못된 요청입니다."),
    INSUFFICIENT_POINTS("INSUFFICIENT_POINTS", "포인트 잔액이 부족합니다."),
    USER_NOT_FOUND("USER_NOT_FOUND", "사용자를 찾을 수 없습니다."),
    BRAND_NOT_FOUND("BRAND_NOT_FOUND", "브랜드를 찾을 수 없습니다."),
    BRAND_HAS_ACTIVE_PRODUCTS("BRAND_HAS_ACTIVE_PRODUCTS", "삭제되지 않은 상품이 있어 브랜드를 삭제할 수 없습니다."),
    PRODUCT_NOT_FOUND("PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다."),
    INSUFFICIENT_STOCK("INSUFFICIENT_STOCK", "상품 재고가 부족합니다."),
    ACCESS_DENIED("ACCESS_DENIED", "접근 권한이 없습니다.");

    private final String code;
    private final String message;
}

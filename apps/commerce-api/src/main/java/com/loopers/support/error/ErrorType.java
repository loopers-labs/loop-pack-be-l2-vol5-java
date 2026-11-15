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
    ACCESS_DENIED("ACCESS_DENIED", "접근 권한이 없습니다."),
    ORDER_AMOUNT_OVERFLOW("ORDER_AMOUNT_OVERFLOW", "주문 금액이 허용 범위를 초과했습니다."),
    ORDER_NOT_FOUND("ORDER_NOT_FOUND", "주문을 찾을 수 없습니다."),
    COUPON_CAMPAIGN_NOT_FOUND("COUPON_CAMPAIGN_NOT_FOUND", "쿠폰 캠페인을 찾을 수 없습니다."),
    COUPON_CAMPAIGN_INACTIVE("COUPON_CAMPAIGN_INACTIVE", "진행 중인 쿠폰 캠페인이 아닙니다."),
    COUPON_SOLD_OUT("COUPON_SOLD_OUT", "쿠폰이 모두 발급되었습니다."),
    COUPON_ADMISSION_FULL("COUPON_ADMISSION_FULL", "현재 쿠폰 발급 요청이 많습니다."),
    COUPON_ADMISSION_UNAVAILABLE("COUPON_ADMISSION_UNAVAILABLE", "쿠폰 발급을 일시적으로 이용할 수 없습니다.");

    private final String code;
    private final String message;
}

package com.loopers.support.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorType {
    /** 범용 에러 */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(), "일시적인 오류가 발생했습니다."),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, HttpStatus.BAD_REQUEST.getReasonPhrase(), "잘못된 요청입니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, HttpStatus.NOT_FOUND.getReasonPhrase(), "존재하지 않는 요청입니다."),
    CONFLICT(HttpStatus.CONFLICT, HttpStatus.CONFLICT.getReasonPhrase(), "이미 존재하는 리소스입니다."),

    /** 공통 업무 에러 */
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 형식이 올바르지 않습니다."),
    NUMERIC_OVERFLOW(HttpStatus.BAD_REQUEST, "NUMERIC_OVERFLOW", "계산 결과가 표현할 수 있는 범위를 넘었습니다."),
    INVALID_PAGE_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_PAGE_REQUEST", "page 는 0 이상, size 는 1~100 이어야 합니다."),
    INVALID_SORT(HttpStatus.BAD_REQUEST, "INVALID_SORT", "지원하지 않는 정렬 기준입니다."),

    /** 포인트 */
    INVALID_POINT_AMOUNT(HttpStatus.BAD_REQUEST, "INVALID_POINT_AMOUNT", "포인트 금액은 1 이상의 정수여야 합니다."),
    INSUFFICIENT_POINT(HttpStatus.CONFLICT, "INSUFFICIENT_POINT", "포인트 잔액이 부족합니다."),
    POINT_NOT_INITIALIZED(HttpStatus.INTERNAL_SERVER_ERROR, "POINT_NOT_INITIALIZED", "포인트 정보가 초기화되지 않았습니다."),

    /** 사용자 */
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "존재하지 않는 사용자입니다."),

    /** 재고 */
    INVALID_STOCK_QUANTITY(HttpStatus.BAD_REQUEST, "INVALID_STOCK_QUANTITY", "재고 수량은 0 이상의 정수여야 합니다."),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", "상품의 재고가 부족합니다."),

    /** 브랜드 */
    BRAND_NOT_FOUND(HttpStatus.NOT_FOUND, "BRAND_NOT_FOUND", "존재하지 않는 브랜드입니다."),
    INVALID_BRAND_NAME(HttpStatus.BAD_REQUEST, "INVALID_BRAND_NAME", "브랜드명은 앞뒤 공백을 제외하고 1~100자여야 합니다."),
    BRAND_HAS_ACTIVE_PRODUCTS(HttpStatus.CONFLICT, "BRAND_HAS_ACTIVE_PRODUCTS", "삭제되지 않은 상품이 연결된 브랜드는 삭제할 수 없습니다."),

    /** 상품 */
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "존재하지 않는 상품입니다."),
    INVALID_PRODUCT_NAME(HttpStatus.BAD_REQUEST, "INVALID_PRODUCT_NAME", "상품명은 앞뒤 공백을 제외하고 1~100자여야 합니다."),
    INVALID_PRODUCT_PRICE(HttpStatus.BAD_REQUEST, "INVALID_PRODUCT_PRICE", "상품 가격은 1~100,000,000원인 정수여야 합니다."),

    /** 좋아요 */
    LIKE_NOT_FOUND(HttpStatus.NOT_FOUND, "LIKE_NOT_FOUND", "존재하지 않는 좋아요 관계입니다."),
    LIKE_ALREADY_EXISTS(HttpStatus.CONFLICT, "LIKE_ALREADY_EXISTS", "이미 좋아요한 상품입니다."),

    /** 주문 */
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "존재하지 않는 주문입니다."),
    ORDER_NOT_CONFIRMABLE(HttpStatus.CONFLICT, "ORDER_NOT_CONFIRMABLE", "확정할 수 없는 상태의 주문입니다."),
    INVALID_ORDER_ITEMS(HttpStatus.BAD_REQUEST, "INVALID_ORDER_ITEMS", "주문 품목은 하나 이상이어야 합니다."),
    INVALID_ORDER_QUANTITY(HttpStatus.BAD_REQUEST, "INVALID_ORDER_QUANTITY", "주문 수량은 1 이상의 정수여야 합니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}

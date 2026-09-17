package com.loopers.support.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 설계 문서 4-4 에러 규약. code 는 enum 이름과 같다.
 * 범용 4개(INTERNAL_ERROR, BAD_REQUEST, NOT_FOUND, CONFLICT)는 example 모듈과 ER-22·500 처리에 쓰인다.
 */
@Getter
public enum ErrorType {
    /** 범용 에러 */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다."),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 요청입니다."),
    CONFLICT(HttpStatus.CONFLICT, "이미 존재하는 리소스입니다."),

    /** ER-01 ~ ER-06 참조·권한 */
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    NOT_ADMIN(HttpStatus.FORBIDDEN, "관리자만 사용할 수 있습니다."),
    BRAND_NOT_FOUND(HttpStatus.NOT_FOUND, "브랜드를 찾을 수 없습니다."),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다."),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
    NOT_OWNER(HttpStatus.FORBIDDEN, "본인의 것만 조회·확정할 수 있습니다."),

    /** ER-07 ~ ER-09 검증 */
    INVALID_SORT(HttpStatus.BAD_REQUEST, "정렬 값이 올바르지 않습니다."),
    INVALID_PAGE(HttpStatus.BAD_REQUEST, "페이지 값이 올바르지 않습니다."),
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "금액이 올바르지 않습니다."),

    /** ER-10 ~ ER-11 포인트 INV */
    BALANCE_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "잔액 한도를 초과합니다."),
    INSUFFICIENT_POINT(HttpStatus.CONFLICT, "포인트가 부족합니다."),

    /** ER-12 ~ ER-16 주문 */
    EMPTY_ORDER_ITEMS(HttpStatus.BAD_REQUEST, "주문 품목이 없습니다."),
    INVALID_QUANTITY(HttpStatus.BAD_REQUEST, "수량이 올바르지 않습니다."),
    AMOUNT_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "주문 금액이 표현 범위를 초과합니다."),
    ORDER_NOT_DRAFT(HttpStatus.CONFLICT, "확정할 수 없는 주문입니다."),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "재고가 부족합니다."),

    /** ER-17 ~ ER-21 브랜드·상품 */
    INVALID_BRAND(HttpStatus.BAD_REQUEST, "브랜드 정보가 올바르지 않습니다."),
    BRAND_HAS_PRODUCTS(HttpStatus.CONFLICT, "상품이 남아 있는 브랜드는 삭제할 수 없습니다."),
    INVALID_PRODUCT_NAME(HttpStatus.BAD_REQUEST, "상품 이름이 올바르지 않습니다."),
    INVALID_PRODUCT_PRICE(HttpStatus.BAD_REQUEST, "상품 가격이 올바르지 않습니다."),
    INVALID_STOCK(HttpStatus.BAD_REQUEST, "재고 수량이 올바르지 않습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorType(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public String getCode() {
        return name();
    }
}

package com.loopers.support.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 오류 코드. 이름이 응답의 {@code meta.errorCode}가 된다.
 * HTTP 상태는 담지 않는다. 상태는 interfaces가 정한다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    /** 요청 형식 */
    INVALID_REQUEST("요청 형식이 올바르지 않습니다. 입력값을 확인해 주세요."),

    /** 값 규칙 */
    INVALID_STOCK_QUANTITY("재고 수량은 0 이상이어야 합니다."),
    INVALID_PRODUCT_NAME("상품 이름은 공백이 아닌 1~100자여야 합니다."),
    INVALID_PRODUCT_PRICE("상품 가격은 1원 이상 1,000,000,000원 이하여야 합니다."),
    INVALID_BRAND_NAME("브랜드 이름은 공백이 아닌 1~50자여야 합니다."),
    INVALID_CHARGE_AMOUNT("충전액은 1 이상이어야 합니다."),
    INVALID_ORDER_QUANTITY("주문 수량은 1 이상이어야 합니다."),
    EMPTY_ORDER_ITEMS("주문할 상품을 하나 이상 담아 주세요."),

    /** 요청자 식별 */
    USER_NOT_IDENTIFIED("사용자를 확인할 수 없습니다."),

    /** 대상 없음 */
    BRAND_NOT_FOUND("브랜드를 찾을 수 없습니다."),
    PRODUCT_NOT_FOUND("상품을 찾을 수 없습니다."),
    ORDER_NOT_FOUND("주문을 찾을 수 없습니다."),
    USER_NOT_FOUND("사용자를 찾을 수 없습니다."),
    LIKE_NOT_FOUND("좋아요를 찾을 수 없습니다."),
    NOT_FOUND("요청한 경로를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED("허용하지 않는 요청 방식입니다."),

    /** 상태 규칙 */
    DUPLICATE_BRAND_NAME("이미 사용 중인 브랜드 이름입니다."),
    DUPLICATE_PRODUCT_NAME("이 브랜드에 같은 이름의 상품이 있습니다."),
    BRAND_HAS_PRODUCTS("연결된 상품이 있어 브랜드를 삭제할 수 없습니다."),
    ORDER_ALREADY_CONFIRMED("이미 확정된 주문입니다."),
    BRAND_CHANGE_NOT_ALLOWED("상품의 브랜드는 바꿀 수 없습니다."),
    PRODUCT_NOT_AVAILABLE("주문한 상품 중 판매하지 않는 상품이 있습니다."),
    INSUFFICIENT_STOCK("재고가 부족합니다."),
    INSUFFICIENT_POINT("포인트 잔액이 부족합니다."),
    POINT_BALANCE_LIMIT_EXCEEDED("충전할 수 있는 한도를 넘었습니다."),

    /** 내부 오류 */
    INTERNAL_ERROR("일시적인 오류가 발생했습니다.");

    private final String message;
}

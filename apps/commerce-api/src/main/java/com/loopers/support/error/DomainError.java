package com.loopers.support.error;

public enum DomainError {

    INSUFFICIENT_STOCK(Failure.RULE_VIOLATION, "재고가 부족합니다."),
    PRODUCT_ALREADY_DELETED(Failure.RULE_VIOLATION, "삭제된 상품은 수정할 수 없습니다."),
    PRODUCT_NOT_FOUND(Failure.UNIDENTIFIED, "상품을 찾을 수 없습니다."),

    INSUFFICIENT_POINT(Failure.RULE_VIOLATION, "포인트 잔액이 부족합니다."),
    POINT_BALANCE_EXCEEDED(Failure.RULE_VIOLATION, "충전하면 잔액이 표현 범위를 넘습니다."),

    BRAND_ALREADY_DELETED(Failure.RULE_VIOLATION, "삭제된 브랜드는 수정할 수 없습니다."),
    BRAND_HAS_PRODUCTS(Failure.RULE_VIOLATION, "삭제되지 않은 상품이 연결된 브랜드는 삭제할 수 없습니다."),
    BRAND_NOT_FOUND(Failure.UNIDENTIFIED, "브랜드를 찾을 수 없습니다."),
    BRAND_NOT_AVAILABLE(Failure.INVALID_REFERENCE, "참조할 수 없는 브랜드입니다."),

    ORDER_NOT_DRAFT(Failure.RULE_VIOLATION, "확정할 수 없는 주문 상태입니다."),
    ORDER_NOT_FOUND(Failure.UNIDENTIFIED, "주문을 찾을 수 없습니다."),

    LIKE_LIST_NOT_FOUND(Failure.UNIDENTIFIED, "좋아요 목록을 찾을 수 없습니다.");

    private final Failure failure;
    private final String message;

    DomainError(Failure failure, String message) {
        this.failure = failure;
        this.message = message;
    }

    public Failure failure() {
        return failure;
    }

    public String message() {
        return message;
    }

    public String code() {
        return name();
    }
}

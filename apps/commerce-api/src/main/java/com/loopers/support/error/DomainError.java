package com.loopers.support.error;

public enum DomainError {

    INSUFFICIENT_STOCK(Failure.RULE_VIOLATION, "재고가 부족합니다."),
    PRODUCT_ALREADY_DELETED(Failure.RULE_VIOLATION, "삭제된 상품은 수정할 수 없습니다."),
    PRODUCT_NOT_FOUND(Failure.UNIDENTIFIED, "상품을 찾을 수 없습니다."),

    POINT_BALANCE_EXCEEDED(Failure.RULE_VIOLATION, "충전하면 잔액이 표현 범위를 넘습니다."),

    BRAND_ALREADY_DELETED(Failure.RULE_VIOLATION, "삭제된 브랜드는 수정할 수 없습니다."),
    BRAND_NOT_FOUND(Failure.UNIDENTIFIED, "브랜드를 찾을 수 없습니다."),
    BRAND_NOT_AVAILABLE(Failure.INVALID_REFERENCE, "참조할 수 없는 브랜드입니다."),

    ORDER_NOT_DRAFT(Failure.RULE_VIOLATION, "확정할 수 없는 주문 상태입니다."),
    ORDER_NOT_CONFIRMED(Failure.RULE_VIOLATION, "확정된 주문이 아닙니다."),
    ORDER_EVENT_CONFLICT(Failure.RULE_VIOLATION, "같은 eventId 로 다른 내용의 이벤트가 이미 있습니다."),
    ORDER_NOT_FOUND(Failure.UNIDENTIFIED, "주문을 찾을 수 없습니다."),

    PAYMENT_NOT_FOUND(Failure.UNIDENTIFIED, "결제 시도를 찾을 수 없습니다."),
    PAYMENT_ATTEMPT_CONFLICT(Failure.RULE_VIOLATION, "같은 결제 시도 키로 다른 요청이 왔습니다."),

    LIKE_LIST_NOT_FOUND(Failure.UNIDENTIFIED, "좋아요 목록을 찾을 수 없습니다."),

    COUPON_CAMPAIGN_NOT_FOUND(Failure.UNIDENTIFIED, "쿠폰 캠페인을 찾을 수 없습니다."),
    COUPON_CAMPAIGN_INACTIVE(Failure.RULE_VIOLATION, "발급 가능한 캠페인이 아닙니다."),
    COUPON_SOLD_OUT(Failure.RULE_VIOLATION, "쿠폰이 모두 발급되었습니다.");

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

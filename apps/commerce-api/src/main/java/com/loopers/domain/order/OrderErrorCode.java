package com.loopers.domain.order;

import com.loopers.support.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements ErrorCode {
    ORDER_NOT_FOUND("존재하지 않는 주문입니다."),
    ORDER_ALREADY_CONFIRMED("이미 확정된 주문입니다."),
    EMPTY_ORDER_ITEMS("주문에는 품목이 하나 이상 있어야 합니다."),
    INVALID_QUANTITY("품목 수량은 1 이상 999 이하여야 합니다."),
    PRODUCT_PRICE_CHANGED("주문을 만든 뒤 상품 가격이 바뀌었습니다. 주문을 다시 만들어 주세요.");

    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}

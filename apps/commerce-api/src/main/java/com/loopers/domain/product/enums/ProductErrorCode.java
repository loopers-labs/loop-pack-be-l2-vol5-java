package com.loopers.domain.product.enums;

import com.loopers.support.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ProductErrorCode implements ErrorCode {
    PRODUCT_NOT_FOUND("존재하지 않는 상품입니다."),
    INVALID_PRODUCT_NAME("상품 이름은 공백뿐일 수 없고 50자 이하여야 합니다."),
    INVALID_PRICE("가격은 0원 이상 1억 원 이하여야 합니다."),
    OUT_OF_STOCK("재고가 부족합니다."),
    INVALID_STOCK("재고는 0 이상이어야 합니다."),
    INVALID_RANKING_PERIOD("주간 랭킹 기간 시작은 월요일이어야 합니다."),
    INVALID_RANKING_LIMIT("주간 랭킹 limit 은 1 이상 100 이하여야 합니다.");

    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}

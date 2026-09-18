package com.loopers.domain.brand;

import com.loopers.support.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum BrandErrorCode implements ErrorCode {
    BRAND_NOT_FOUND("존재하지 않는 브랜드입니다."),
    INVALID_BRAND_NAME("브랜드 이름은 공백뿐일 수 없고 20자 이하여야 합니다."),
    INVALID_BRAND_DESCRIPTION("브랜드 설명은 255자 이하여야 합니다."),
    BRAND_HAS_PRODUCTS("삭제되지 않은 상품이 남은 브랜드는 삭제할 수 없습니다.");

    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}

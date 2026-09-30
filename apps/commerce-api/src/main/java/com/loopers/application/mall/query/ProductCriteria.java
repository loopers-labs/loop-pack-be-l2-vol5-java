package com.loopers.application.mall.query;

import com.loopers.application.common.PageCriteria;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;

// 상품 목록 조회 조건
public record ProductCriteria(Long brandId, ProductSort sort, PageCriteria page) {
    public ProductCriteria {
        if (brandId != null && brandId <= 0) {
            throw new DomainException(DomainErrorCode.INVALID_QUANTITY, "브랜드 ID는 양의 정수여야 합니다.");
        }
    }
}

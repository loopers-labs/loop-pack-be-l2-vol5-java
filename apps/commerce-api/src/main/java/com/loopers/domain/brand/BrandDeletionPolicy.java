package com.loopers.domain.brand;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

public class BrandDeletionPolicy {

    public void delete(Brand brand, boolean hasActiveProducts) {
        if (brand.getDeletedAt() != null) {
            throw new CoreException(ErrorType.NOT_FOUND, "브랜드를 찾을 수 없습니다.");
        }
        if (hasActiveProducts) {
            throw new CoreException(ErrorType.CONFLICT, "삭제되지 않은 상품이 연결되어 있습니다.");
        }

        brand.delete();
    }
}

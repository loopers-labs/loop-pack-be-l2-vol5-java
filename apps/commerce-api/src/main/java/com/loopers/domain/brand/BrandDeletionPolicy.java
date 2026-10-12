package com.loopers.domain.brand;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

public class BrandDeletionPolicy {

    public void delete(Brand brand) {
        if (brand.getDeletedAt() != null) {
            throw new CoreException(ErrorType.NOT_FOUND, "브랜드를 찾을 수 없습니다.");
        }

        brand.delete();
    }
}

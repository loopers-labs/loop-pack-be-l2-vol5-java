package com.loopers.brand.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;

import java.util.Objects;

public class BrandNameValidator {

    private final BrandRepository brandRepository;

    public BrandNameValidator(BrandRepository brandRepository) {
        this.brandRepository = brandRepository;
    }

    public void validateNotDuplicated(String name) {
        validateNotDuplicated(name, null);
    }

    public void validateNotDuplicated(String name, Long excludeBrandId) {
        boolean duplicated = brandRepository.findAllByName(name).stream()
            .anyMatch(brand -> !brand.isDeleted()
                && brand.getName().equals(name)
                && (excludeBrandId == null || !Objects.equals(brand.getId(), excludeBrandId)));
        if (duplicated) {
            throw new CoreException(ErrorCode.DUPLICATE_BRAND_NAME);
        }
    }
}

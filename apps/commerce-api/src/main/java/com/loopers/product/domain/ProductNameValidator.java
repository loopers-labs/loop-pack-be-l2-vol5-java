package com.loopers.product.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;

import java.util.Objects;

public class ProductNameValidator {

    private final ProductRepository productRepository;

    public ProductNameValidator(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public void validateNotDuplicated(Long brandId, String name) {
        validateNotDuplicated(brandId, name, null);
    }

    public void validateNotDuplicated(Long brandId, String name, Long excludeProductId) {
        boolean duplicated = productRepository.findAllByBrandIdAndName(brandId, name).stream()
            .anyMatch(product -> !product.isDeleted()
                && product.getName().equals(name)
                && (excludeProductId == null || !Objects.equals(product.getId(), excludeProductId)));
        if (duplicated) {
            throw new CoreException(ErrorCode.DUPLICATE_PRODUCT_NAME);
        }
    }
}

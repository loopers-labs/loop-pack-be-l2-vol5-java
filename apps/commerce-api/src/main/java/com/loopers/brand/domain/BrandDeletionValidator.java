package com.loopers.brand.domain;

import com.loopers.product.domain.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;

public class BrandDeletionValidator {

    private final ProductRepository productRepository;

    public BrandDeletionValidator(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public void validateDeletable(Brand brand) {
        boolean hasActiveProduct = productRepository.findAllByBrandId(brand.getId()).stream()
            .anyMatch(product -> !product.isDeleted());
        if (hasActiveProduct) {
            throw new CoreException(ErrorCode.BRAND_HAS_PRODUCTS);
        }
    }
}

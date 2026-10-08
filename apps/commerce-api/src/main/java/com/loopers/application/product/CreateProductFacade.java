package com.loopers.application.product;

import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional
public class CreateProductFacade {
    private final ProductRepository repository;
    private final BrandRepository brands;

    public ProductInfo create(Long brandId, String name, Long price) {
        validateRequest(brandId, price);
        requireActiveBrand(brandId);
        Product product = Product.create(brandId, name, price);

        return ProductInfo.from(repository.save(product));
    }

    private void validateRequest(Long brandId, Long price) {
        if (brandId == null || brandId <= 0 || price == null) {
            throw new CoreException(ErrorType.INVALID_REQUEST);
        }
    }

    private void requireActiveBrand(long brandId) {
        brands.findByIdForUpdate(brandId)
                .orElseThrow(() -> new CoreException(ErrorType.BRAND_NOT_FOUND))
                .requireActive();
    }
}

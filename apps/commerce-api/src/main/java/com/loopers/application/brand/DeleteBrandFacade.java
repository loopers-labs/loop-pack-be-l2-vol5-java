package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional
public class DeleteBrandFacade {
    private final BrandRepository repository;
    private final ProductRepository products;

    public void delete(long id) {
        Brand brand = findBrandForDeletion(id);
        deleteAssociatedProducts(id);
        brand.delete();
        repository.save(brand);
    }

    private Brand findBrandForDeletion(long id) {
        return repository
                .findByIdForUpdate(id)
                .orElseThrow(() -> new CoreException(ErrorType.BRAND_NOT_FOUND));
    }

    private void deleteAssociatedProducts(long brandId) {
        for (long productId : products.findActiveIdsByBrandId(brandId)) {
            products.delete(productId);
        }
    }
}

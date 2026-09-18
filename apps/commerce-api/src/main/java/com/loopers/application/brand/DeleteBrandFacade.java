package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
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
    private final BrandProductLookup products;

    public void delete(long id) {
        Brand brand = repository.findById(id).orElseThrow(() -> new CoreException(ErrorType.BRAND_NOT_FOUND));
        brand.delete(!brand.isDeleted() && products.hasActiveProducts(id));
        repository.save(brand);
    }
}

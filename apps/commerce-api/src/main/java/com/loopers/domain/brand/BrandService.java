package com.loopers.domain.brand;

import com.loopers.domain.common.PageNumber;
import com.loopers.domain.common.PageSize;
import com.loopers.domain.common.PageWindow;
import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.domain.product.ProductsInBrand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BrandService {

    private final BrandRepository brandRepository;

    private final ProductsInBrand productsInBrand;

    public Brand register(String name, String description) {
        return brandRepository.save(Brand.register(name, description));
    }

    public Brand get(Long brandId) {
        return findAlive(brandId);
    }

    public Brand update(Long brandId, String name, String description) {
        Brand brand = findAlive(brandId);
        brand.update(name, description);
        return brandRepository.save(brand);
    }

    public void delete(Long brandId) {
        Brand brand = findAliveForUpdate(brandId);
        productsInBrand.deleteAll(brandId);
        brand.delete();
        brandRepository.save(brand);
    }

    public PageWindow<Brand> findPage(PageNumber page, PageSize size) {
        return brandRepository.findPage(page, size);
    }

    public void requireAvailable(Long brandId) {
        brandRepository.findByIdForShare(brandId)
            .orElseThrow(() -> new DomainException(DomainError.BRAND_NOT_AVAILABLE));
    }

    private Brand findAliveForUpdate(Long brandId) {
        return brandRepository.findByIdForUpdate(brandId)
            .orElseThrow(() -> new DomainException(DomainError.BRAND_NOT_FOUND));
    }

    private Brand findAlive(Long brandId) {
        return brandRepository.findById(brandId)
            .orElseThrow(() -> new DomainException(DomainError.BRAND_NOT_FOUND));
    }
}

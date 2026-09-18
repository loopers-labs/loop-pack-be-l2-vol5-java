package com.loopers.domain.brand;

import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.domain.product.ProductsInBrand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

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
        if (productsInBrand.hasAlive(brandId)) {
            throw new DomainException(DomainError.BRAND_HAS_PRODUCTS);
        }
        brand.delete();
        brandRepository.save(brand);
    }

    public List<Brand> findPage(int offset, int limit) {
        return brandRepository.findPage(offset, limit);
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

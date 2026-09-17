package com.loopers.fixture;

import com.loopers.domain.brand.BrandModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import org.springframework.stereotype.Component;

@Component
public class BrandFixture {

    private final BrandJpaRepository brandJpaRepository;

    public BrandFixture(BrandJpaRepository brandJpaRepository) {
        this.brandJpaRepository = brandJpaRepository;
    }

    public BrandModel createBrand(String name) {
        return brandJpaRepository.save(BrandModel.create(name));
    }

    public BrandModel createDeletedBrand(String name) {
        BrandModel brand = createBrand(name);
        brand.delete(false);
        return brandJpaRepository.save(brand);
    }
}

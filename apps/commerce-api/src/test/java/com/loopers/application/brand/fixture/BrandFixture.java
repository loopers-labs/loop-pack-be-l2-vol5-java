package com.loopers.application.brand.fixture;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.infrastructure.brand.BrandJpaRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
public class BrandFixture {
    @Autowired private BrandRepository brands;
    @Autowired private BrandJpaRepository brandRows;

    public Brand createBrand() {
        return createBrand("브랜드");
    }

    public Brand createBrand(String name) {
        return brands.save(Brand.create(name));
    }

    public void deleteBrand(long id) {
        Brand brand = brands.findById(id).orElseThrow();
        brand.delete();
        brands.save(brand);
    }

    @Transactional(readOnly = true)
    public Brand brand(long id) {
        return brands.findById(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public long rowCount() {
        return brandRows.count();
    }
}

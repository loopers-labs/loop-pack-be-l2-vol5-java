package com.loopers.application.brand;

import com.loopers.domain.brand.BrandService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class BrandFacade {
    private final BrandService brandService;

    public Page<BrandInfo> getBrands(Pageable pageable) {
        return brandService.getActiveBrands(pageable).map(BrandInfo::from);
    }

    public BrandInfo getBrand(Long brandId) {
        return BrandInfo.from(brandService.getActiveBrand(brandId));
    }

    public BrandInfo createBrand(String name, String description) {
        return BrandInfo.from(brandService.create(name, description));
    }

    public BrandInfo updateBrand(Long brandId, String name, String description) {
        return BrandInfo.from(brandService.update(brandId, name, description));
    }

    public void deleteBrand(Long brandId) {
        brandService.delete(brandId);
    }
}

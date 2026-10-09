package com.loopers.application.brand;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class BrandFacade {
    private final BrandService brandService;
    private final ProductService productService;

    public BrandInfo getBrand(Long id) {
        BrandModel brand = brandService.getBrand(id);
        return BrandInfo.from(brand);
    }

    public BrandAdminInfo getBrandForAdmin(Long id) {
        BrandModel brand = brandService.getBrandForAdmin(id);
        return BrandAdminInfo.from(brand);
    }

    public Page<BrandAdminInfo> getBrands(Pageable pageable) {
        return brandService.getBrands(pageable).map(BrandAdminInfo::from);
    }

    public BrandAdminInfo createBrand(String name) {
        BrandModel brand = brandService.createBrand(name);
        return BrandAdminInfo.from(brand);
    }

    public BrandAdminInfo updateBrand(Long id, String name) {
        BrandModel brand = brandService.updateBrand(id, name);
        return BrandAdminInfo.from(brand);
    }

    @Transactional
    public void deleteBrand(Long id) {
        // 잠금 순서: 브랜드 → 상품
        brandService.getBrandForUpdate(id);
        productService.deleteAllActiveByBrand(id);
        brandService.deleteBrand(id);
    }
}

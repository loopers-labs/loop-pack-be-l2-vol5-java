package com.loopers.application.brand;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.product.ProductService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

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

    public void deleteBrand(Long id) {
        if (productService.hasActiveProductsByBrand(id)) {
            throw new CoreException(ErrorType.CONFLICT, "[id = " + id + "] 삭제되지 않은 상품이 연결된 브랜드는 삭제할 수 없습니다.");
        }
        brandService.deleteBrand(id);
    }
}

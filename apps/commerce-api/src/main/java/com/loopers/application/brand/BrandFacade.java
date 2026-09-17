package com.loopers.application.brand;

import com.loopers.domain.brand.BrandService;
import com.loopers.domain.product.ProductService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class BrandFacade {

    private final BrandService brandService;
    private final ProductService productService;

    public BrandInfo getBrand(Long brandId) {
        return BrandInfo.from(brandService.getActiveBrand(brandId));
    }

    public List<BrandAdminInfo> getBrandsForAdmin() {
        return brandService.getBrands().stream().map(BrandAdminInfo::from).toList();
    }

    public BrandAdminInfo getBrandForAdmin(Long brandId) {
        return BrandAdminInfo.from(brandService.getBrand(brandId));
    }

    public BrandAdminInfo createBrand(String name) {
        return BrandAdminInfo.from(brandService.create(name));
    }

    public BrandAdminInfo updateBrand(Long brandId, String name) {
        return BrandAdminInfo.from(brandService.update(brandId, name));
    }

    public void deleteBrand(Long brandId) {
        if (productService.hasActiveProductsOfBrand(brandId)) {
            throw new CoreException(ErrorType.BAD_REQUEST,
                "[id = " + brandId + "] 삭제되지 않은 상품이 연결된 브랜드는 삭제할 수 없습니다.");
        }
        brandService.delete(brandId);
    }
}

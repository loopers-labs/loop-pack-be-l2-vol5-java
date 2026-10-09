package com.loopers.application.brand;

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

    /**
     * 브랜드와 그 브랜드의 삭제되지 않은 상품(재고 0 포함)을 함께 삭제함. 브랜드와 상품 두 도메인을 다루므로 트랜잭션을 여기서 엶.
     * 하나라도 실패하면 브랜드와 상품 모두 삭제 전 상태로 남음 (BRD-02, 3주차 설계 2.2)
     */
    @Transactional
    public void deleteBrand(Long brandId) {
        brandService.delete(brandId);
        productService.deleteAllOfBrand(brandId);
    }
}

package com.loopers.application.product;

import com.loopers.domain.brand.BrandService;
import com.loopers.domain.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class AdminProductFacade {
    private final ProductService productService;
    private final BrandService brandService;

    /** 관리자 필터는 관리할 대상을 지정하므로, 없거나 삭제된 브랜드면 BRAND_NOT_FOUND 로 알린다 (설계 D-24). */
    public Page<AdminProductInfo> getProducts(Long brandId, Pageable pageable) {
        if (brandId != null) {
            brandService.getActiveBrand(brandId);
        }
        return productService.getActiveProductsWithBrand(brandId, pageable).map(AdminProductInfo::from);
    }

    public AdminProductInfo getProduct(Long productId) {
        return AdminProductInfo.from(productService.getActiveProductWithBrand(productId));
    }

    public AdminProductInfo createProduct(Long brandId, String name, long price) {
        Long productId = productService.create(brandId, name, price).getId();
        return getProduct(productId);
    }

    public AdminProductInfo updateProduct(Long productId, String name, long price) {
        productService.update(productId, name, price);
        return getProduct(productId);
    }

    public AdminProductInfo changeStock(Long productId, int stock) {
        productService.changeStock(productId, stock);
        return getProduct(productId);
    }

    public void deleteProduct(Long productId) {
        productService.delete(productId);
    }
}

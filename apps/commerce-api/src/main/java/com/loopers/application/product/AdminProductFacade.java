package com.loopers.application.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * 살아 있는 브랜드를 공유 잠금으로 조회해 상품을 만듦. 등록 중에 브랜드가 삭제되어 삭제된 브랜드에 상품이 남는 일을 막음.
     * 브랜드와 상품 두 도메인을 다루므로 트랜잭션을 여기서 엶 (BRD-02, 설계 4.4, 3주차 설계 4.2)
     */
    @Transactional
    public AdminProductInfo createProduct(Long brandId, String name, long price) {
        Brand brand = brandService.getActiveBrandForShare(brandId);
        Long productId = productService.create(brand, name, price).getId();
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

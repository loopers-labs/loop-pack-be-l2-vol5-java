package com.loopers.application.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductListCache;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.StockChange;
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
    private final ProductListCache productListCache;

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
     * 브랜드와 상품 두 도메인을 다루므로 트랜잭션을 여기서 엶 (BRD-02, 설계 4.4, 3주차 설계 4.2).
     * 목록 캐시 무효화는 이 트랜잭션의 commit 뒤에 일어남 (4주차 cache-design.md 3.3, 3.4)
     */
    @Transactional
    public AdminProductInfo createProduct(Long brandId, String name, long price) {
        Brand brand = brandService.getActiveBrandForShare(brandId);
        Long productId = productService.create(brand, name, price).getId();
        productListCache.invalidate(brandId);
        return getProduct(productId);
    }

    // 아래 쓰기는 ProductService 의 트랜잭션이 commit 된 뒤 무효화함 (4주차 cache-design.md 3.4)
    public AdminProductInfo updateProduct(Long productId, String name, long price) {
        Product product = productService.update(productId, name, price);
        productListCache.invalidate(product.getBrandId());
        return getProduct(productId);
    }

    /** 품절 여부가 바뀔 때만 무효화함. 캐시는 재고 수량을 담지 않음 (4주차 cache-design.md 3.3) */
    public AdminProductInfo changeStock(Long productId, int stock) {
        StockChange change = productService.changeStock(productId, stock);
        if (change.soldOutChanged()) {
            productListCache.invalidate(change.product().getBrandId());
        }
        return getProduct(productId);
    }

    public void deleteProduct(Long productId) {
        Product product = productService.delete(productId);
        productListCache.invalidate(product.getBrandId());
    }
}

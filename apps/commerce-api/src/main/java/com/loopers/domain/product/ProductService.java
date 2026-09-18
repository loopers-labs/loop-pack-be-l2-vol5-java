package com.loopers.domain.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class ProductService {

    private final ProductRepository productRepository;
    private final BrandService brandService;

    /**
     * 없거나 삭제된 상품은 PRODUCT_NOT_FOUND. 다른 도메인도 이 조회를 쓴다 (설계 D-31).
     * 여러 품목 중 어느 상품인지 알 수 있도록 상품 식별자를 부가 정보로 담는다 (설계 6.4).
     */
    @Transactional(readOnly = true)
    public Product getActiveProduct(Long productId) {
        return productRepository.findActive(productId)
            .orElseThrow(() -> new CoreException(ProductErrorCode.PRODUCT_NOT_FOUND, null, ProductErrorDetail.of(productId)));
    }

    @Transactional(readOnly = true)
    public ProductWithBrand getActiveProductWithBrand(Long productId) {
        return productRepository.findActiveWithBrand(productId)
            .orElseThrow(() -> new CoreException(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public Page<ProductWithBrand> getActiveProductsWithBrand(Long brandId, Pageable pageable) {
        return productRepository.findActiveWithBrand(brandId, pageable);
    }

    @Transactional(readOnly = true)
    public ProductView getActiveProductView(Long productId) {
        return productRepository.findActiveView(productId)
            .orElseThrow(() -> new CoreException(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public Page<ProductView> getActiveProductViews(Long brandId, ProductSort sort, Pageable pageable) {
        return productRepository.findActiveViews(brandId, sort, pageable);
    }

    /** 살아 있는 브랜드는 BrandService 로 조회하고, 생성 입구의 확인은 Product 가 한 번 더 한다 (BRD-02). */
    @Transactional
    public Product create(Long brandId, String name, long price) {
        Brand brand = brandService.getActiveBrand(brandId);
        return productRepository.save(new Product(brand, name, price));
    }

    @Transactional
    public Product update(Long productId, String name, long price) {
        Product product = getActiveProduct(productId);
        product.update(name, price);
        return product;
    }

    @Transactional
    public Product changeStock(Long productId, int stock) {
        Product product = getActiveProduct(productId);
        product.changeStock(stock);
        return product;
    }

    @Transactional
    public void delete(Long productId) {
        getActiveProduct(productId).delete();
    }
}

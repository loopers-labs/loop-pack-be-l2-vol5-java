package com.loopers.application.product;

import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 상품 유스케이스. 삭제된 상품은 조회·수정·재고 변경·삭제의 대상이 아니다 (PRD-06, P-10).
 */
@RequiredArgsConstructor
@Component
public class ProductAdminFacade {

    private final ProductRepository productRepository;
    private final BrandRepository brandRepository;

    /**
     * PRD-02: 존재하며 삭제되지 않은 브랜드를 참조해야 한다.
     */
    @Transactional
    public ProductAdminInfo create(Long brandId, String name, long price, int stock) {
        brandRepository.findActiveById(brandId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[brandId = " + brandId + "] 브랜드를 찾을 수 없습니다."));
        ProductModel saved = productRepository.save(new ProductModel(brandId, name, price, stock));
        return ProductAdminInfo.from(saved);
    }

    @Transactional(readOnly = true)
    public Page<ProductAdminInfo> getProducts(Long brandId, Pageable pageable) {
        return productRepository.findActive(brandId, pageable).map(ProductAdminInfo::from);
    }

    @Transactional(readOnly = true)
    public ProductAdminInfo getProduct(Long productId) {
        return ProductAdminInfo.from(getActiveProduct(productId));
    }

    @Transactional
    public ProductAdminInfo update(Long productId, String name, long price) {
        ProductModel product = getActiveProduct(productId);
        product.update(name, price);
        return ProductAdminInfo.from(productRepository.save(product));
    }

    @Transactional
    public ProductAdminInfo changeStock(Long productId, int stock) {
        ProductModel product = getActiveProduct(productId);
        product.changeStock(stock);
        return ProductAdminInfo.from(productRepository.save(product));
    }

    @Transactional
    public void delete(Long productId) {
        getActiveProduct(productId).delete();
    }

    private ProductModel getActiveProduct(Long productId) {
        return productRepository.findActiveById(productId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[productId = " + productId + "] 상품을 찾을 수 없습니다."));
    }
}

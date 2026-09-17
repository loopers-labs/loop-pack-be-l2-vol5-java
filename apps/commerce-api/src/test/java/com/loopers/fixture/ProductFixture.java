package com.loopers.fixture;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.product.ProductJpaRepository;
import org.springframework.stereotype.Component;

/**
 * 주문·좋아요·상품 조회 테스트에서 사용할 상품을 준비한다.
 * 상품은 항상 실제 Brand 에 연결해 저장한다.
 */
@Component
public class ProductFixture {

    private final ProductJpaRepository productJpaRepository;
    private final BrandFixture brandFixture;

    public ProductFixture(ProductJpaRepository productJpaRepository, BrandFixture brandFixture) {
        this.productJpaRepository = productJpaRepository;
        this.brandFixture = brandFixture;
    }

    /** 상품마다 새 브랜드를 만들어 연결한다. */
    public ProductModel createProduct(String name, long price, long stock) {
        BrandModel brand = brandFixture.createBrand(name + " 브랜드");
        return createProduct(brand.getId(), name, price, stock);
    }

    public ProductModel createProduct(Long brandId, String name, long price, long stock) {
        ProductModel product = ProductModel.create(brandId, name, price);
        product.changeStock(stock);
        return productJpaRepository.save(product);
    }

    /** 이미 저장된 상품을 Soft Delete 한다. */
    public void deleteProduct(Long productId) {
        ProductModel product = productJpaRepository.findById(productId).orElseThrow();
        product.delete();
        productJpaRepository.save(product);
    }

    public ProductModel createDeletedProduct(String name, long price, long stock) {
        ProductModel product = createProduct(name, price, stock);
        deleteProduct(product.getId());
        return productJpaRepository.findById(product.getId()).orElseThrow();
    }
}

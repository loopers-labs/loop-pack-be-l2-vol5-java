package com.loopers.application.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class ProductFacade {

    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public ProductInfo getDetail(Long productId) {
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));
        return ProductInfo.from(product);
    }

    @Transactional
    public ProductInfo register(Long brandId, String name, long price) {
        Brand brand = findActiveBrandById(brandId);
        Product product = Product.create(brand, name, price);

        Product savedProduct = productRepository.save(product);
        return ProductInfo.from(savedProduct);
    }

    @Transactional
    public ProductInfo changeStock(Long productId, long quantity) {
        Product product = findActiveProductById(productId);
        product.changeStockTo(quantity);

        Product savedProduct = productRepository.save(product);
        return ProductInfo.from(savedProduct);
    }

    @Transactional
    public ProductInfo update(Long productId, String name, long price) {
        Product product = findActiveProductById(productId);
        product.updateDetails(name, price);

        Product savedProduct = productRepository.save(product);
        return ProductInfo.from(savedProduct);
    }

    @Transactional
    public void delete(Long productId) {
        Product product = findActiveProductById(productId);
        product.delete();
        productRepository.save(product);
    }

    private Brand findActiveBrandById(Long brandId) {
        if (brandId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "브랜드 ID는 필수입니다.");
        }

        Brand brand = brandRepository.findById(brandId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "브랜드를 찾을 수 없습니다."));
        if (brand.getDeletedAt() != null) {
            throw new CoreException(ErrorType.NOT_FOUND, "브랜드를 찾을 수 없습니다.");
        }
        return brand;
    }

    private Product findActiveProductById(Long productId) {
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));
        if (product.getDeletedAt() != null) {
            throw new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다.");
        }
        return product;
    }
}
